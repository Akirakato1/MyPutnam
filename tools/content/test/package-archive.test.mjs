import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';

import { ZipFile } from 'yazl';
import { fromBufferPromise } from 'yauzl';

import {
  createPackageArchive,
  verifyPackageArchive,
} from '../src/package-archive.mjs';
import { PACKAGE_LIMITS } from '../src/package-contract.mjs';

const member = (path, text, mediaType = 'application/json') => ({
  path, mediaType, bytes: Buffer.from(text, 'utf8'),
});

async function sampleArchive(members = [
  member('source/problem.json', '{"problemId":"A1"}\n'),
  member('documents/statement.fragment.html', '<p>Statement</p>\n', 'text/html; charset=utf-8'),
]) {
  return createPackageArchive({
    contentType: 'problem', contentKey: 'putnam/2025/A1', sourceSchemaVersion: 3,
    richDocumentVersion: 1, minAppVersionCode: 1, members,
  });
}

function descriptorFor(result) {
  return {
    contentType: result.envelope.contentType,
    packageRevision: result.envelope.packageRevision,
    packageSchemaVersion: result.envelope.packageSchemaVersion,
    contentSchemaVersion: result.envelope.contentSchemaVersion,
    richDocumentVersion: result.envelope.richDocumentVersion,
    path: `packages/problem/${result.envelope.packageRevision.slice(0, 2)}/${result.envelope.packageRevision}.zip`,
    compressedBytes: result.bytes.byteLength,
    uncompressedBytes: result.envelope.declaredUncompressedBytes,
    sha256: createHash('sha256').update(result.bytes).digest('hex'),
    minAppVersionCode: result.envelope.minAppVersionCode,
  };
}

test('archive bytes are deterministic across input order and expose fixed sorted metadata', async (t) => {
  const inputs = [
    member('source/problem.json', '{"b":2,"a":1}\n'),
    member('documents/statement.fragment.html', '<p>One</p>\n', 'text/html; charset=utf-8'),
  ];
  const first = await sampleArchive(inputs);
  const second = await sampleArchive([...inputs].reverse());
  assert.deepEqual(first.envelope, second.envelope);
  assert.deepEqual(first.bytes, second.bytes);

  const entries = await centralDirectory(first.bytes);
  assert.deepEqual(entries.map((entry) => entry.fileName), [
    'documents/statement.fragment.html', 'package.json', 'source/problem.json',
  ]);
  for (const entry of entries) {
    assert.equal(entry.lastModFileDate, 33, entry.fileName);
    assert.equal(entry.lastModFileTime, 0, entry.fileName);
    assert.equal((entry.externalFileAttributes >>> 16) & 0o177777, 0o100644, entry.fileName);
  }
  const verified = await verifyPackageArchive(first.bytes, { expectedDescriptor: null });
  assert.equal(Buffer.from(verified.members.get('source/problem.json')).toString('utf8'), '{"a":1,"b":2}\n');
  t.diagnostic(`fixture sha256 ${createHash('sha256').update(first.bytes).digest('hex')}`);
});

test('round trip independently verifies declared members, hashes, sizes, and descriptor', async () => {
  const result = await sampleArchive();
  const verified = await verifyPackageArchive(result.bytes, { expectedDescriptor: descriptorFor(result) });
  assert.deepEqual(verified.envelope, result.envelope);
  assert.deepEqual([...verified.members.keys()], [
    'documents/statement.fragment.html', 'source/problem.json',
  ]);
  assert.equal(Buffer.from(verified.members.get('source/problem.json')).toString('utf8'), '{"problemId":"A1"}\n');
});

test('creator rejects duplicate, case-colliding, reserved, and unsafe member paths', async () => {
  for (const paths of [
    ['source/a.json', 'source/a.json'], ['assets/A.svg', 'assets/a.svg'],
    ['package.json'], ['/absolute'], ['../escape'], ['a/../b'], ['C:/drive'], ['a\\b'], ['a//b'],
  ]) {
    await assert.rejects(sampleArchive(paths.map((path) => member(path, '{}'))), /path|duplicate|package\.json/i, paths.join(','));
  }
  await assert.rejects(sampleArchive([member('source/problem.json', '{invalid')]), /invalid.*JSON/i);
});

test('verifier rejects descriptor drift, outer limits, malformed ZIPs, and payload corruption', async () => {
  const result = await sampleArchive();
  const descriptor = descriptorFor(result);
  const drifted = { ...descriptor, sha256: '0'.repeat(64), uncompressedBytes: descriptor.uncompressedBytes + 1 };
  await assert.rejects(verifyPackageArchive(result.bytes, { expectedDescriptor: drifted }), /sha256|uncompressedBytes|descriptor/i);
  await assert.rejects(verifyPackageArchive(result.bytes, {
    expectedDescriptor: descriptor,
    limits: { ...PACKAGE_LIMITS, maxCompressedBytes: result.bytes.byteLength - 1 },
  }), /compressed/i);
  await assert.rejects(verifyPackageArchive(Buffer.from('not a zip'), { expectedDescriptor: null }), /zip|central|invalid/i);
  const corrupted = Buffer.from(result.bytes);
  corrupted[Math.floor(corrupted.length / 3)] ^= 0xff;
  await assert.rejects(verifyPackageArchive(corrupted, { expectedDescriptor: null }), /hash|CRC|invalid|integrity|package/i);
});

test('verifier rejects undeclared files, directories, symlinks, special entries, and entry limits', async () => {
  const result = await sampleArchive();
  const original = await readAllEntries(result.bytes);

  const undeclared = await rawZip([
    ...original.map(({ path, bytes }) => ({ path, bytes })),
    { path: 'extra.txt', bytes: Buffer.from('extra') },
  ]);
  await assert.rejects(verifyPackageArchive(undeclared, { expectedDescriptor: null }), /undeclared|member/i);

  const directory = await rawZip([
    ...original.map(({ path, bytes }) => ({ path, bytes })),
    { path: 'assets/', directory: true },
  ]);
  await assert.rejects(verifyPackageArchive(directory, { expectedDescriptor: null }), /directory|special/i);

  const symlink = await rawZip([
    ...original.map(({ path, bytes }) => ({ path, bytes })),
    { path: 'link', bytes: Buffer.from('target'), mode: 0o120777 },
  ]);
  await assert.rejects(verifyPackageArchive(symlink, { expectedDescriptor: null }), /symlink|special|undeclared/i);

  await assert.rejects(verifyPackageArchive(result.bytes, {
    expectedDescriptor: null, limits: { ...PACKAGE_LIMITS, maxEntries: 2 },
  }), /entry count/i);
  await assert.rejects(verifyPackageArchive(result.bytes, {
    expectedDescriptor: null, limits: { ...PACKAGE_LIMITS, maxEntryBytes: 5 },
  }), /entry bytes/i);
});

async function centralDirectory(bytes) {
  const zip = await fromBufferPromise(Buffer.from(bytes), { lazyEntries: true, strictFileNames: true });
  const entries = [];
  try {
    for await (const entry of zip.eachEntry()) entries.push(entry);
  } finally {
    zip.close();
  }
  return entries;
}

async function readAllEntries(bytes) {
  const zip = await fromBufferPromise(Buffer.from(bytes), { lazyEntries: true, strictFileNames: true });
  const entries = [];
  try {
    for await (const entry of zip.eachEntry()) {
      const stream = await zip.openReadStreamPromise(entry);
      const chunks = [];
      for await (const chunk of stream) chunks.push(chunk);
      entries.push({ path: entry.fileName, bytes: Buffer.concat(chunks) });
    }
  } finally {
    zip.close();
  }
  return entries;
}

async function rawZip(entries) {
  const zip = new ZipFile();
  for (const entry of [...entries].sort((left, right) => left.path.localeCompare(right.path))) {
    if (entry.directory) zip.addEmptyDirectory(entry.path, { mtime: new Date('1980-01-01T00:00:00.000Z'), mode: 0o40755, forceDosTimestamp: true });
    else zip.addBuffer(entry.bytes, entry.path, {
      mtime: new Date('1980-01-01T00:00:00.000Z'), mode: entry.mode ?? 0o100644,
      forceDosTimestamp: true, compressionLevel: 9,
    });
  }
  zip.end({ forceZip64Format: false });
  const chunks = [];
  for await (const chunk of zip.outputStream) chunks.push(chunk);
  return Buffer.concat(chunks);
}

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { auditSourceLocks } from '../src/audit-source-locks.mjs';

test('correct PDF and TeX locks pass, and PDF page count is optional', async (t) => {
  const fixture = await createSourceFixture(t);
  assert.deepEqual(await auditSourceLocks(fixture.root), []);

  delete fixture.manifest.sources[0].pageCount;
  await writeManifest(fixture);
  assert.deepEqual(await auditSourceLocks(fixture.root), []);
});

test('changed bytes are detected even when the file length is unchanged', async (t) => {
  const fixture = await createSourceFixture(t);
  const path = join(fixture.root, fixture.manifest.sources[1].file);
  const bytes = await readFile(path);
  bytes[bytes.length - 1] ^= 1;
  await writeFile(path, bytes);

  const errors = await auditSourceLocks(fixture.root);
  assert.ok(errors.some((error) => error.includes('sources.json.sources[1].sha256')), errors.join('\n'));
  assert.equal(errors.some((error) => error.includes('sources.json.sources[1].byteLength')), false);
});

test('wrong declared byte length and SHA-256 are path-qualified', async (t) => {
  const fixture = await createSourceFixture(t);
  fixture.manifest.sources[0].byteLength += 1;
  fixture.manifest.sources[1].sha256 = '0'.repeat(64);
  await writeManifest(fixture);

  const errors = await auditSourceLocks(fixture.root);
  assert.ok(errors.some((error) => error.includes('sources.json.sources[0].byteLength')), errors.join('\n'));
  assert.ok(errors.some((error) => error.includes('sources.json.sources[1].sha256')), errors.join('\n'));
});

test('missing files, duplicate IDs, unsafe paths, and insecure URLs are rejected', async (t) => {
  const fixture = await createSourceFixture(t);
  await unlink(join(fixture.root, fixture.manifest.sources[0].file));
  fixture.manifest.sources[1].id = fixture.manifest.sources[0].id;
  fixture.manifest.sources[1].file = '../outside.tex';
  fixture.manifest.sources[1].url = 'http://example.test/source.tex';
  await writeManifest(fixture);

  const errors = await auditSourceLocks(fixture.root);
  assert.ok(errors.some((error) => error.includes('sources.json.sources[0].file') && error.includes('missing')), errors.join('\n'));
  assert.ok(errors.some((error) => error.includes('sources.json.sources[1].id') && error.includes('duplicate')), errors.join('\n'));
  assert.ok(errors.some((error) => error.includes('sources.json.sources[1].file') && error.includes('safe local path')), errors.join('\n'));
  assert.ok(errors.some((error) => error.includes('sources.json.sources[1].url') && error.includes('https://')), errors.join('\n'));
  assert.deepEqual(errors, [...errors].sort((left, right) => left.localeCompare(right)));
});

test('a wrong optional PDF page count is rejected', async (t) => {
  const fixture = await createSourceFixture(t);
  fixture.manifest.sources[0].pageCount = 2;
  await writeManifest(fixture);

  const errors = await auditSourceLocks(fixture.root);
  assert.ok(errors.some((error) => error.includes('sources.json.sources[0].pageCount')), errors.join('\n'));
});

async function createSourceFixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'putnam-source-lock-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(join(root, 'original'), { recursive: true });

  const pdf = Buffer.from('%PDF-1.4\n1 0 obj\n<< /Type /Page >>\nendobj\n%%EOF\n', 'latin1');
  const tex = Buffer.from('Putnam solution source\n', 'utf8');
  await Promise.all([
    writeFile(join(root, 'original', 'problems.pdf'), pdf),
    writeFile(join(root, 'original', 'solutions.tex'), tex),
  ]);

  const manifest = {
    year: 2024,
    publisher: 'Fixture publisher',
    usage: 'Personal, noncommercial offline study only.',
    sources: [
      lock('problems', 'original/problems.pdf', 'https://example.test/problems.pdf', pdf, 1),
      lock('solutions-tex', 'original/solutions.tex', 'https://example.test/solutions.tex', tex),
    ],
  };
  const fixture = { root, manifest };
  await writeManifest(fixture);
  return fixture;
}

function lock(id, file, url, bytes, pageCount) {
  return {
    id,
    title: id,
    publisher: 'Fixture publisher',
    url,
    retrievedOn: '2026-10-02',
    file,
    byteLength: bytes.byteLength,
    sha256: createHash('sha256').update(bytes).digest('hex'),
    ...(pageCount === undefined ? {} : { pageCount }),
  };
}

async function writeManifest({ root, manifest }) {
  await writeFile(join(root, 'sources.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');
}

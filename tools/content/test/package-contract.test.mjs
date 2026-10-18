import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
  PACKAGE_LIMITS,
  PACKAGE_SCHEMA_VERSION,
  validatePackageDescriptor,
  validatePackageEnvelope,
  validatePackageMemberPath,
  validatePackagePair,
} from '../src/package-contract.mjs';

const fixtures = new URL('./fixtures/contracts/', import.meta.url);
const fixture = async (name) => JSON.parse(await readFile(new URL(name, fixtures), 'utf8'));

test('package contract exposes pinned version and limits', () => {
  assert.equal(PACKAGE_SCHEMA_VERSION, 1);
  assert.deepEqual(PACKAGE_LIMITS, {
    maxCompressedBytes: 16 * 1024 * 1024,
    maxUncompressedBytes: 64 * 1024 * 1024,
    maxEntries: 256,
    maxEntryBytes: 16 * 1024 * 1024,
  });
});

test('package envelope accepts canonical declarations and safe member paths', async () => {
  assert.deepEqual(validatePackageEnvelope(await fixture('valid-package-envelope.json')), []);
  for (const path of ['content.json', 'assets/diagram.svg']) assert.equal(validatePackageMemberPath(path), true);
  for (const path of ['', '/absolute', '../escape', 'assets/../escape', 'C:/drive', 'https://host/a', 'a\\b']) {
    assert.equal(validatePackageMemberPath(path), false, path);
  }
});

test('package envelope rejects unknown fields, duplicate paths, limits, and incompatible schemas', async () => {
  const value = await fixture('valid-package-envelope.json');
  value.unexpected = true;
  value.contentSchemaVersion = 2;
  value.members.push({ ...value.members[0], bytes: PACKAGE_LIMITS.maxEntryBytes + 1 });
  value.declaredUncompressedBytes += PACKAGE_LIMITS.maxEntryBytes + 1;
  const errors = validatePackageEnvelope(value);
  assert.ok(errors.some((error) => error.startsWith('$.unexpected:')));
  assert.ok(errors.some((error) => error.includes('unsupported schema combination')));
  assert.ok(errors.some((error) => error.includes('duplicate package member path')));
  assert.ok(errors.some((error) => error.includes('exceeds maximum entry bytes')));
});

test('descriptor is relative, strict, compatible, and matches its envelope', async () => {
  const envelope = await fixture('valid-package-envelope.json');
  const descriptor = {
    contentType: 'problem', packageRevision: envelope.packageRevision,
    packageSchemaVersion: 1, contentSchemaVersion: 3, richDocumentVersion: 1,
    path: `packages/problem/aa/${envelope.packageRevision}.zip`, compressedBytes: 100,
    uncompressedBytes: 123, sha256: 'd'.repeat(64), minAppVersionCode: 1,
  };
  assert.deepEqual(validatePackageDescriptor(descriptor), []);
  assert.deepEqual(validatePackagePair(envelope, descriptor), []);

  descriptor.path = 'https://example.test/package.zip';
  descriptor.extra = true;
  descriptor.uncompressedBytes = 124;
  const errors = [...validatePackageDescriptor(descriptor), ...validatePackagePair(envelope, descriptor)];
  assert.ok(errors.some((error) => error.startsWith('$.path:')));
  assert.ok(errors.some((error) => error.startsWith('$.extra:')));
  assert.ok(errors.some((error) => error.includes('uncompressedBytes does not match')));
});

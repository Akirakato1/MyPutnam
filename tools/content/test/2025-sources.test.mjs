import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { auditSourceLocks } from '../src/audit-source-locks.mjs';

const yearRoot = new URL('../../../content-src/2025/', import.meta.url);

test('the locked 2025 source set passes the reusable source audit', async () => {
  assert.deepEqual(await auditSourceLocks(yearRoot), []);
});

test('every 2025 source lock records the request, final URL, and media type', async () => {
  const manifest = JSON.parse(await readFile(new URL('sources.json', yearRoot), 'utf8'));
  for (const source of manifest.sources) {
    assert.match(source.requestUrl, /^https:\/\//);
    assert.match(source.url, /^https:\/\//);
    assert.equal(typeof source.mediaType, 'string');
    assert.notEqual(source.mediaType.length, 0);
  }
});

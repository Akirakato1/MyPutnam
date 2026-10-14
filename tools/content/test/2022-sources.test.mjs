import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { auditSourceLocks } from '../src/audit-source-locks.mjs';

const yearRoot = new URL('../../../content-src/2022/', import.meta.url);

test('the locked 2022 source set passes the reusable source audit', async () => {
  assert.deepEqual(await auditSourceLocks(yearRoot), []);
});

test('the 2022 source set locks authoritative PDFs and independent TeX cross-checks', async () => {
  const manifest = JSON.parse(await readFile(new URL('sources.json', yearRoot), 'utf8'));
  assert.deepEqual(manifest.sources.map(({ id }) => id), [
    'problems',
    'solutions',
    'kedlaya-statements',
    'kedlaya-solutions',
  ]);
  assert.deepEqual(manifest.sources.filter(({ mediaType }) => mediaType === 'application/pdf').map(({ pageCount }) => pageCount), [2, 23]);
  for (const source of manifest.sources) {
    assert.match(source.requestUrl, /^https:\/\//);
    assert.match(source.url, /^https:\/\//);
  }
});

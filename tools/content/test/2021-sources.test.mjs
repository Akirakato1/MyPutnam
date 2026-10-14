import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { auditSourceLocks } from '../src/audit-source-locks.mjs';

const yearRoot = new URL('../../../content-src/2021/', import.meta.url);

test('the locked 2021 source set passes the reusable source audit', async () => {
  assert.deepEqual(await auditSourceLocks(yearRoot), []);
});

test('the 2021 source set distinguishes the standalone paper from the combined official solutions', async () => {
  const manifest = JSON.parse(await readFile(new URL('sources.json', yearRoot), 'utf8'));
  assert.deepEqual(manifest.sources.map(({ id }) => id), [
    'problems',
    'solutions',
    'kedlaya-statements',
    'kedlaya-solutions',
  ]);
  assert.deepEqual(
    manifest.sources.filter(({ mediaType }) => mediaType === 'application/pdf').map(({ pageCount }) => pageCount),
    [2, 17],
  );
  assert.match(manifest.sources[0].title, /Problems for Sessions A and B/);
  assert.match(manifest.sources[1].title, /Official Problems and Solutions/);
  assert.match(manifest.sources[1].file, /problems-and-solutions\.pdf$/);
  for (const source of manifest.sources) {
    assert.match(source.requestUrl, /^https:\/\//);
    assert.match(source.url, /^https:\/\//);
  }
});

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { auditSourceLocks } from '../src/audit-source-locks.mjs';
import { problemKey } from '../src/content-types.mjs';
import { findCatalogSet, loadLibraryCatalog } from '../src/library-catalog.mjs';

const yearRoot = new URL('../../../content-src/2020/', import.meta.url);

test('the locked 2020 source set passes the reusable source audit', async () => {
  assert.deepEqual(await auditSourceLocks(yearRoot), []);
});

test('the 2020 source set distinguishes the standalone paper from the official solutions', async () => {
  const manifest = JSON.parse(await readFile(new URL('sources.json', yearRoot), 'utf8'));
  assert.deepEqual(manifest.sources.map(({ id }) => id), [
    'problems',
    'solutions',
    'kedlaya-statements',
    'kedlaya-solutions',
  ]);
  assert.deepEqual(
    manifest.sources.filter(({ mediaType }) => mediaType === 'application/pdf').map(({ pageCount }) => pageCount),
    [2, 20],
  );
  assert.match(manifest.sources[0].title, /Problems for Sessions A and B/);
  assert.match(manifest.sources[1].title, /Official Problems and Solutions/);
  for (const source of manifest.sources) {
    assert.match(source.requestUrl, /^https:\/\//);
    assert.match(source.url, /^https:\/\//);
  }
});

test('the delayed 2020 sitting retains its 2020 catalog identity and canonical keys', async () => {
  const catalog = await loadLibraryCatalog(new URL('../../../content-src/library.json', import.meta.url));
  const { problemSet } = findCatalogSet(catalog, 'putnam', '2020');
  assert.equal(problemSet.id, '2020');
  assert.equal(problemSet.title, '2020');
  assert.equal(problemSet.metadata.competitionYear, 2020);
  assert.equal(problemSet.metadata.administrationYear, 2021);
  assert.equal(problemKey('putnam', problemSet.id, 'A1'), 'putnam/2020/A1');
});

import assert from 'node:assert/strict';
import test from 'node:test';

import { auditYear } from '../src/audit-year.mjs';
import { loadArchiveCatalog } from '../src/archive-catalog.mjs';
import { findCatalogSet, loadLibraryCatalog } from '../src/library-catalog.mjs';

const yearRoot = new URL('../../../content-src/2019/', import.meta.url);
const archiveUrl = new URL('../../../content-src/archive.json', import.meta.url);
const catalogUrl = new URL('../../../content-src/library.json', import.meta.url);

test('the catalog reserves the complete 2015–2019 curation batch', async () => {
  const catalog = await loadLibraryCatalog(catalogUrl);
  for (const year of [2019, 2018, 2017, 2016, 2015]) {
    const { problemSet } = findCatalogSet(catalog, 'putnam', String(year));
    assert.equal(problemSet.title, String(year));
    assert.equal(problemSet.metadata.competitionYear, year);
  }
});

test('the complete 2019 corpus passes the strict reusable year audit', async () => {
  const catalog = await loadLibraryCatalog(catalogUrl);
  assert.deepEqual(await auditYear(yearRoot, 2019, { catalog }), []);
});

test('the archive records 2019 as a completed year', async () => {
  const archive = await loadArchiveCatalog(archiveUrl);
  assert.ok(archive.completedYears.includes(2019));
});

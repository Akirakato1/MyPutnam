import assert from 'node:assert/strict';
import test from 'node:test';

import { auditYear } from '../src/audit-year.mjs';
import { findCatalogSet, loadLibraryCatalog } from '../src/library-catalog.mjs';

const yearRoot = new URL('../../../content-src/2019/', import.meta.url);
const catalogUrl = new URL('../../../content-src/library.json', import.meta.url);

test('the catalog reserves the complete 2015–2019 curation batch', async () => {
  const catalog = await loadLibraryCatalog(catalogUrl);
  for (const year of [2019, 2018, 2017, 2016, 2015]) {
    const { problemSet } = findCatalogSet(catalog, 'putnam', String(year));
    assert.equal(problemSet.title, String(year));
    assert.equal(problemSet.metadata.competitionYear, year);
  }
});

test('the curated 2019 A1–A3 checkpoint passes the reusable year audit', async () => {
  assert.deepEqual(await auditYear(yearRoot, 2019, {
    problems: ['A1', 'A2', 'A3'],
    requireCompleteYear: false,
  }), []);
});

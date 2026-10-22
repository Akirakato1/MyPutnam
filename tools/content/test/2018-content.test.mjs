import assert from 'node:assert/strict';
import test from 'node:test';

import { auditYear } from '../src/audit-year.mjs';
import { findCatalogSet, loadLibraryCatalog } from '../src/library-catalog.mjs';

const yearRoot = new URL('../../../content-src/2018/', import.meta.url);
const catalogUrl = new URL('../../../content-src/library.json', import.meta.url);

test('the catalog reserves 2018 in the historical curation batch', async () => {
  const catalog = await loadLibraryCatalog(catalogUrl);
  const { problemSet } = findCatalogSet(catalog, 'putnam', '2018');
  assert.equal(problemSet.title, '2018');
  assert.equal(problemSet.metadata.competitionYear, 2018);
});

test('the curated 2018 A1–A6 checkpoint passes the reusable year audit', async () => {
  assert.deepEqual(await auditYear(yearRoot, 2018, {
    problems: ['A1', 'A2', 'A3', 'A4', 'A5', 'A6'],
    requireCompleteYear: false,
  }), []);
});

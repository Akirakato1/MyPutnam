import assert from 'node:assert/strict';
import test from 'node:test';

import {
  assertFinalArchiveComplete,
  loadArchiveCatalog,
  validateArchiveCatalog,
} from '../src/archive-catalog.mjs';

const catalogUrl = new URL('../../../content-src/archive.json', import.meta.url);
const targetYears = Array.from({ length: 2025 - 1962 + 1 }, (_, index) => 1962 + index);

test('the canonical archive catalog targets every year from 1962 through 2025', async () => {
  const catalog = await loadArchiveCatalog(catalogUrl);

  assert.equal(catalog.schemaVersion, 1);
  assert.equal(catalog.contentSchemaVersion, 2);
  assert.equal(catalog.contentVersion, 'archive-1962-2025.1');
  assert.deepEqual(catalog.targetYears, targetYears);
  assert.deepEqual(catalog.completedYears, [2025]);
  assert.deepEqual(validateArchiveCatalog(catalog), []);
});

test('target and completed years must be unique and strictly ascending', () => {
  const duplicateTarget = validCatalog();
  duplicateTarget.targetYears = [1962, 1962, 2025];

  const descendingCompleted = validCatalog();
  descendingCompleted.completedYears = [2025, 2024];

  assert.ok(
    validateArchiveCatalog(duplicateTarget).some((error) => error.includes('targetYears')),
  );
  assert.ok(
    validateArchiveCatalog(descendingCompleted).some((error) => error.includes('completedYears')),
  );
});

test('completed years must be a subset of target years', () => {
  const catalog = validCatalog();
  catalog.completedYears = [2026];

  assert.ok(
    validateArchiveCatalog(catalog).some(
      (error) => error.includes('completedYears') && error.includes('2026'),
    ),
  );
});

test('final archive validation rejects incomplete catalogs without an escape hatch', () => {
  const incomplete = validCatalog();

  assert.throws(
    () => assertFinalArchiveComplete(incomplete),
    /incomplete.*63.*year/i,
  );

  const complete = validCatalog();
  complete.completedYears = [...complete.targetYears];
  assert.doesNotThrow(() => assertFinalArchiveComplete(complete));
});

function validCatalog() {
  return {
    schemaVersion: 1,
    contentSchemaVersion: 2,
    contentVersion: 'archive-1962-2025.1',
    targetYears: [...targetYears],
    completedYears: [2025],
  };
}

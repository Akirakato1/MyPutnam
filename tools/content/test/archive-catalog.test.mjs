import assert from 'node:assert/strict';
import test from 'node:test';

import {
  assertFinalArchiveComplete,
  loadArchiveCatalog,
  validateArchiveCatalog,
} from '../src/archive-catalog.mjs';
import { assertReleaseComplete } from '../src/release-manifest.mjs';

const catalogUrl = new URL('../../../content-src/archive.json', import.meta.url);
const targetYears = Array.from({ length: 2025 - 1962 + 1 }, (_, index) => 1962 + index);
const completedBatchYears = [2020, 2021, 2022, 2023, 2024, 2025];

test('the canonical archive catalog targets every year from 1962 through 2025', async () => {
  const catalog = await loadArchiveCatalog(catalogUrl);

  assert.equal(catalog.schemaVersion, 1);
  assert.equal(catalog.contentSchemaVersion, 3);
  assert.equal(catalog.contentVersion, 'archive-1962-2025.1');
  assert.deepEqual(catalog.targetYears, targetYears);
  assert.deepEqual(catalog.completedYears, completedBatchYears);
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
    /incomplete.*58.*year/i,
  );

  const complete = validCatalog();
  complete.completedYears = [...complete.targetYears];
  assert.doesNotThrow(() => assertFinalArchiveComplete(complete));
});

test('release completion is independent from final archive completion', () => {
  const archive = validCatalog();
  const release = {
    schemaVersion: 1,
    releaseId: 'putnam-2020-2025-v0.4.0',
    contentSchemaVersion: 3,
    contentVersion: 'putnam-2020-2025.1',
    appVersionName: '0.4.0',
    appVersionCode: 4,
    includedSets: completedBatchYears.map((year) => `putnam/${year}`),
  };

  assert.throws(() => assertFinalArchiveComplete(archive), /incomplete/i);
  assert.doesNotThrow(() => assertReleaseComplete(release, archive));

  archive.completedYears = archive.completedYears.filter((year) => year !== 2022);
  assert.throws(
    () => assertReleaseComplete(release, archive),
    /putnam\/2022.*not complete/i,
  );
});

function validCatalog() {
  return {
    schemaVersion: 1,
    contentSchemaVersion: 3,
    contentVersion: 'archive-1962-2025.1',
    targetYears: [...targetYears],
    completedYears: [...completedBatchYears],
  };
}

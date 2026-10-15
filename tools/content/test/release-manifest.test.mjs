import assert from 'node:assert/strict';
import test from 'node:test';

import { loadArchiveCatalog } from '../src/archive-catalog.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';
import {
  assertReleaseComplete,
  loadReleaseManifest,
  validateReleaseManifest,
} from '../src/release-manifest.mjs';
const archiveUrl = new URL('../../../content-src/archive.json', import.meta.url);
const libraryUrl = new URL('../../../content-src/library.json', import.meta.url);
const releaseUrl = new URL(
  '../../../content-src/releases/putnam-2020-2025-v0.4.0.json',
  import.meta.url,
);

test('the travel release selects exactly six complete sets while older archive targets remain incomplete', async () => {
  requireContract();
  const archive = await loadArchiveCatalog(archiveUrl);
  const library = await loadLibraryCatalog(libraryUrl);
  const release = await loadReleaseManifest(releaseUrl, archive, library);

  assert.deepEqual(release, validRelease());
  assert.deepEqual(validateReleaseManifest(release, archive, library), []);
  assert.doesNotThrow(() => assertReleaseComplete(release, archive));
});

test('manifest validation rejects malformed identities, versions, and set selections', async () => {
  requireContract();
  const archive = await loadArchiveCatalog(archiveUrl);
  const library = await loadLibraryCatalog(libraryUrl);
  const cases = [
    ['schema version', { schemaVersion: 2 }, 'schemaVersion'],
    ['release id', { releaseId: '../travel' }, 'releaseId'],
    ['content schema', { contentSchemaVersion: 2 }, 'contentSchemaVersion'],
    ['content version', { contentVersion: 'putnam-2020-2025.2' }, 'contentVersion'],
    ['app version name', { appVersionName: '0.4.1' }, 'appVersionName'],
    ['app version code', { appVersionCode: 5 }, 'appVersionCode'],
    ['unsafe set key', { includedSets: ['putnam/2020', '../putnam/2021'] }, 'includedSets[1]'],
    ['malformed set key', { includedSets: ['putnam/2020/extra'] }, 'includedSets[0]'],
    ['duplicate set key', { includedSets: ['putnam/2020', 'putnam/2020'] }, 'includedSets[1]'],
    ['unsorted set keys', { includedSets: ['putnam/2021', 'putnam/2020'] }, 'includedSets[1]'],
    ['unknown set key', { includedSets: ['putnam/2019'] }, 'includedSets[0]'],
  ];

  for (const [label, change, expectedPath] of cases) {
    const release = { ...validRelease(), ...change };
    const errors = validateReleaseManifest(release, archive, library);
    assert.ok(errors.some((error) => error.includes(expectedPath)), `${label}: ${errors.join('\n')}`);
  }
});

test('manifest validation rejects duplicate catalog collection and set spellings', async () => {
  requireContract();
  const archive = await loadArchiveCatalog(archiveUrl);
  const library = await loadLibraryCatalog(libraryUrl);

  const duplicateCollection = structuredClone(library);
  duplicateCollection.collections.push(structuredClone(duplicateCollection.collections[0]));
  assert.ok(
    validateReleaseManifest(validRelease(), archive, duplicateCollection)
      .some((error) => error.includes('duplicate collection id putnam')),
  );

  const duplicateSet = structuredClone(library);
  duplicateSet.collections[0].sets.push(structuredClone(duplicateSet.collections[0].sets[0]));
  assert.ok(
    validateReleaseManifest(validRelease(), archive, duplicateSet)
      .some((error) => error.includes('duplicate set id 2025')),
  );
});

test('release completion rejects a selected Putnam year absent from completedYears', async () => {
  requireContract();
  const archive = await loadArchiveCatalog(archiveUrl);
  const library = await loadLibraryCatalog(libraryUrl);
  archive.completedYears = archive.completedYears.filter((year) => year !== 2023);

  const errors = validateReleaseManifest(validRelease(), archive, library);
  assert.ok(errors.some((error) => error.includes('includedSets[3]') && error.includes('2023')));
  assert.throws(() => assertReleaseComplete(validRelease(), archive), /putnam\/2023.*not complete/i);
});

test('extra completed archive years neither enter nor block an explicit release', async () => {
  requireContract();
  const archive = await loadArchiveCatalog(archiveUrl);
  const library = await loadLibraryCatalog(libraryUrl);
  archive.completedYears = [2019, ...archive.completedYears];

  assert.deepEqual(validateReleaseManifest(validRelease(), archive, library), []);
  assert.doesNotThrow(() => assertReleaseComplete(validRelease(), archive));
  assert.deepEqual(validRelease().includedSets, [
    'putnam/2020',
    'putnam/2021',
    'putnam/2022',
    'putnam/2023',
    'putnam/2024',
    'putnam/2025',
  ]);
});

test('a completed Putnam target outside the library catalog is still rejected', async () => {
  requireContract();
  const archive = await loadArchiveCatalog(archiveUrl);
  const library = await loadLibraryCatalog(libraryUrl);
  archive.completedYears = [2019, ...archive.completedYears];
  const release = { ...validRelease(), includedSets: ['putnam/2019'] };

  const errors = validateReleaseManifest(release, archive, library);
  assert.ok(errors.some((error) => error.includes('includedSets[0]') && error.includes('library catalog')));
});

function requireContract() {
  assert.equal(typeof validateReleaseManifest, 'function', 'validateReleaseManifest must exist');
  assert.equal(typeof loadReleaseManifest, 'function', 'loadReleaseManifest must exist');
  assert.equal(typeof assertReleaseComplete, 'function', 'assertReleaseComplete must exist');
}

function validRelease() {
  return {
    schemaVersion: 1,
    releaseId: 'putnam-2020-2025-v0.4.0',
    contentSchemaVersion: 3,
    contentVersion: 'putnam-2020-2025.1',
    appVersionName: '0.4.0',
    appVersionCode: 4,
    includedSets: [
      'putnam/2020',
      'putnam/2021',
      'putnam/2022',
      'putnam/2023',
      'putnam/2024',
      'putnam/2025',
    ],
  };
}

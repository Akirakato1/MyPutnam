import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
  buildPublicationCatalog,
  validatePublicationCatalog,
  validatePublicationManifest,
} from '../src/publication-catalog.mjs';

const fixtures = new URL('./fixtures/contracts/', import.meta.url);
const fixture = async (name) => JSON.parse(await readFile(new URL(name, fixtures), 'utf8'));

test('publication catalog accepts a strict relative package feed', async () => {
  assert.deepEqual(validatePublicationCatalog(await fixture('valid-publication-catalog.json')), []);
});

test('publication catalog rejects unknown taxonomy, duplicates, unsafe paths, and missing rights', async () => {
  const value = await fixture('valid-publication-catalog.json');
  const duplicate = structuredClone(value.problems[0]);
  duplicate.topics = ['unknown'];
  duplicate.package.path = '../escape.zip';
  delete duplicate.rights.attribution;
  value.problems.push(duplicate);
  const errors = validatePublicationCatalog(value);
  assert.ok(errors.some((error) => error.includes('duplicate problem key')));
  assert.ok(errors.some((error) => error.includes('unknown topic unknown')));
  assert.ok(errors.some((error) => error.includes('package.path')));
  assert.ok(errors.some((error) => error.includes('rights.attribution')));
});

test('publication manifest is strict and the builder is deterministic', async () => {
  const manifest = {
    schemaVersion: 1, catalogId: 'myputnam-public',
    catalogRevision: 'c'.repeat(64), generatedAt: '2026-10-18T00:00:00.000Z',
    minAppVersionCode: 1,
    includedSets: [{ collectionId: 'putnam', setId: '2025' }],
    includedReferenceCategories: ['number-theory'],
  };
  assert.deepEqual(validatePublicationManifest(manifest), []);

  const expected = await fixture('valid-publication-catalog.json');
  const libraryCatalog = {
    schemaVersion: 1, contentSchemaVersion: 3,
    collections: [{
      id: 'putnam', title: 'Putnam', kind: 'competition', displayOrder: 1,
      rights: structuredClone(expected.problems[0].rights),
      sets: [{ id: '2025', title: '2025', displayOrder: 1, metadata: { competitionYear: 2025 } }],
    }],
    topics: structuredClone(expected.topics),
    referenceCategories: structuredClone(expected.referenceCategories),
  };
  const problemPackages = [{
    catalogMetadata: {
      key: 'putnam/2025/A1', collectionId: 'putnam', setId: '2025', problemId: 'A1',
      label: 'A1', ordinal: 1, topics: ['number-theory'],
    },
    descriptor: structuredClone(expected.problems[0].package),
  }];
  assert.deepEqual(buildPublicationCatalog({
    publicationManifest: manifest, libraryCatalog, problemPackages, referencePackages: [],
  }), expected);
});

test('publication manifest rejects duplicate selections and unknown fields', () => {
  const value = {
    schemaVersion: 1, catalogId: 'catalog', catalogRevision: 'a'.repeat(64),
    generatedAt: '2026-10-18T00:00:00.000Z', minAppVersionCode: 1,
    includedSets: [{ collectionId: 'putnam', setId: '2025' }, { collectionId: 'putnam', setId: '2025' }],
    includedReferenceCategories: ['number-theory', 'number-theory'], unexpected: true,
  };
  const errors = validatePublicationManifest(value);
  assert.ok(errors.some((error) => error.startsWith('$.unexpected:')));
  assert.ok(errors.some((error) => error.includes('duplicate included set')));
  assert.ok(errors.some((error) => error.includes('duplicate included reference category')));
});

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  isSafeId,
  parseProblemKey,
  parseSetKey,
  problemKey,
  setKey,
} from '../src/content-types.mjs';
import { findCatalogSet, validateLibraryCatalog } from '../src/library-catalog.mjs';

const catalog = () => ({
  schemaVersion: 1,
  contentSchemaVersion: 3,
  collections: [
    {
      id: 'putnam', title: 'Putnam', kind: 'competition', displayOrder: 1,
      sets: [
        { id: '2025', title: '2025', displayOrder: 1, metadata: { competitionYear: 2025 } },
        { id: '2024', title: '2024', displayOrder: 2, metadata: { competitionYear: 2024 } },
      ],
    },
    {
      id: 'Course', title: 'Course', kind: 'course', displayOrder: 2,
      sets: [{ id: 'chapter-1', title: 'Chapter One', displayOrder: 1 }],
    },
  ],
  topics: [
    { id: 'algebra', label: 'Algebra', displayOrder: 1 },
    { id: 'integrals', label: 'Integrals', displayOrder: 2 },
  ],
});

test('safe ids and canonical keys preserve exact case', () => {
  for (const id of ['putnam', '2025', 'A1', 'chapter-1', 'x.y_z']) assert.equal(isSafeId(id), true, id);
  for (const id of ['', '.', '..', ' A1', 'A1 ', 'A/1', 'A\\1', 'A%1', 'A:1', 'A?1']) {
    assert.equal(isSafeId(id), false, id);
  }

  assert.equal(setKey('Course', 'chapter-1'), 'Course/chapter-1');
  assert.equal(problemKey('Course', 'chapter-1', 'P1'), 'Course/chapter-1/P1');
  assert.deepEqual(parseSetKey('Course/chapter-1'), { collectionId: 'Course', setId: 'chapter-1' });
  assert.deepEqual(parseProblemKey('Course/chapter-1/P1'), {
    collectionId: 'Course', setId: 'chapter-1', problemId: 'P1',
  });
  assert.throws(() => parseProblemKey('course/chapter-1'));
  assert.throws(() => parseProblemKey('course/chapter-1/P1/extra'));
});

test('catalog validation rejects duplicate ids and display orders without case folding', () => {
  assert.deepEqual(validateLibraryCatalog(catalog()), []);

  const caseDistinct = catalog();
  caseDistinct.collections.push({ id: 'course', title: 'Lowercase course', kind: 'course', displayOrder: 3, sets: [] });
  assert.deepEqual(validateLibraryCatalog(caseDistinct), []);

  for (const mutate of [
    (value) => value.collections.push({ ...value.collections[0], title: 'Duplicate collection', displayOrder: 3 }),
    (value) => value.collections[0].sets.push({ id: 'new', title: 'New', displayOrder: 1 }),
    (value) => value.topics.push({ id: 'algebra', label: 'Duplicate topic', displayOrder: 3 }),
    (value) => value.topics.push({ id: 'geometry', label: 'Geometry', displayOrder: 2 }),
  ]) {
    const invalid = catalog();
    mutate(invalid);
    assert.ok(validateLibraryCatalog(invalid).length > 0);
  }
});

test('administration year records delayed sittings without changing canonical competition identity', () => {
  const delayed = catalog();
  delayed.collections[0].sets.push({
    id: '2020',
    title: '2020',
    displayOrder: 3,
    metadata: { competitionYear: 2020, administrationYear: 2021 },
  });

  assert.deepEqual(validateLibraryCatalog(delayed), []);
  const { problemSet } = findCatalogSet(delayed, 'putnam', '2020');
  assert.equal(problemSet.metadata.competitionYear, 2020);
  assert.equal(problemSet.metadata.administrationYear, 2021);
  assert.equal(problemKey('putnam', problemSet.id, 'A1'), 'putnam/2020/A1');

  const backwards = structuredClone(delayed);
  backwards.collections[0].sets[2].metadata.administrationYear = 2019;
  assert.match(
    validateLibraryCatalog(backwards).join('\n'),
    /administrationYear.*earlier than competitionYear/,
  );

  const fractional = structuredClone(delayed);
  fractional.collections[0].sets[2].metadata.administrationYear = 2021.5;
  assert.ok(validateLibraryCatalog(fractional).length > 0);
});

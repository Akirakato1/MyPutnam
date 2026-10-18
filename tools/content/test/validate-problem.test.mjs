import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { SCHEMA_VERSION } from '../src/content-types.mjs';
import { validateProblem as validateProblemRecord } from '../src/validate-problem.mjs';

const fixtureUrl = new URL('./fixtures/valid-problem.json', import.meta.url);
const validProblem = JSON.parse(await readFile(fixtureUrl, 'utf8'));
const catalog = {
  schemaVersion: 1,
  contentSchemaVersion: 3,
  collections: [{
    id: 'putnam',
    title: 'William Lowell Putnam Mathematical Competition',
    kind: 'competition',
    displayOrder: 1,
    sets: [{ id: '2025', title: '2025', displayOrder: 1, metadata: { competitionYear: 2025 } }],
  }],
  topics: [
    { id: 'algebra', label: 'Algebra', displayOrder: 1 },
    { id: 'integrals', label: 'Integrals', displayOrder: 2 },
  ],
};

function validateProblem(record) {
  return validateProblemRecord(record, catalog);
}

test('a complete problem record passes validation', () => {
  assert.equal(SCHEMA_VERSION, 3);
  assert.equal(validProblem.schemaVersion, 3);
  assert.equal('originalPdf' in validProblem, false);
  assert.deepEqual(validProblem.hints.map(({ stage }) => stage), [
    'nudge',
    'strategy',
    'knowledge',
    'outline',
  ]);
  assert.deepEqual(validateProblem(validProblem), []);
});

test('the canonical problem template is a valid schema-v3 record', async () => {
  const template = JSON.parse(
    await readFile(new URL('../templates/problem.template.json', import.meta.url), 'utf8'),
  );

  assert.equal(template.schemaVersion, 3);
  assert.deepEqual(validateProblem(template), []);
});

test('schema v2 and missing hint stages are rejected at exact paths', () => {
  const schemaV2 = structuredClone(validProblem);
  schemaV2.schemaVersion = 2;
  const missingStage = structuredClone(validProblem);
  delete missingStage.hints[0].stage;

  assert.ok(
    validateProblem(schemaV2).some((error) => error.startsWith('$.schemaVersion:')),
  );
  assert.ok(
    validateProblem(missingStage).some((error) => error.startsWith('$.hints[0].stage:')),
  );
});

test('a problem must contain exactly four hints', () => {
  const threeHints = structuredClone(validProblem);
  threeHints.hints = threeHints.hints.slice(0, 3);

  const fiveHints = structuredClone(validProblem);
  fiveHints.hints.push({
    order: 5,
    title: 'Extra hint',
    document: { blocks: [{ type: 'paragraph', runs: [{ type: 'text', text: 'Too many.' }] }] },
  });

  assert.ok(validateProblem(threeHints).some((error) => error.includes('$.hints')));
  assert.ok(validateProblem(fiveHints).some((error) => error.includes('$.hints')));
});

test('hint order must be unique and exactly 1 through 4', () => {
  const duplicateOrder = structuredClone(validProblem);
  duplicateOrder.hints[1].order = 1;

  const errors = validateProblem(duplicateOrder);

  assert.ok(errors.some((error) => error.includes('$.hints[1].order')));
  assert.ok(errors.some((error) => error.includes('duplicate')));
});

test('hint stages are unique and fixed to their canonical orders', () => {
  const mismatchedStage = structuredClone(validProblem);
  mismatchedStage.hints[1].stage = 'knowledge';

  const errors = validateProblem(mismatchedStage);

  assert.ok(
    errors.some((error) => error.includes('$.hints[1].stage: expected strategy')),
    errors.join('\n'),
  );
  assert.ok(
    errors.some((error) => error.includes('$.hints[2].stage: duplicate stage knowledge')),
    errors.join('\n'),
  );
});

test('originalPdf is not part of schema v3', () => {
  const withOriginalPdf = structuredClone(validProblem);
  withOriginalPdf.originalPdf = { path: 'original/problems.pdf', pageIndex: 0 };

  const errors = validateProblem(withOriginalPdf);

  assert.ok(errors.some((error) => error.startsWith('$.originalPdf:')), errors.join('\n'));
});

test('generic ids, ordinals, topics, and prerequisites follow schema v3', () => {
  const genericCatalog = structuredClone(catalog);
  genericCatalog.collections.push({
    id: 'course', title: 'Course', kind: 'course', displayOrder: 2,
    sets: [{ id: 'chapter-1', title: 'Chapter One', displayOrder: 1 }],
  });
  const generic = structuredClone(validProblem);
  Object.assign(generic, {
    collectionId: 'course',
    setId: 'chapter-1',
    problemId: 'P17',
    label: 'Challenge 17',
    ordinal: 37,
    topics: ['integrals', 'analysis-not-in-catalog'],
  });

  const unknownTopicErrors = validateProblemRecord(generic, genericCatalog);
  assert.ok(unknownTopicErrors.some((error) => error.includes('unknown topic analysis-not-in-catalog')));
  generic.topics = ['integrals'];
  assert.deepEqual(validateProblemRecord(generic, genericCatalog), []);

  generic.topics = ['integrals', 'integrals'];
  assert.ok(validateProblemRecord(generic, genericCatalog).some((error) => error.includes('duplicate topic')));
  generic.topics = [];
  assert.ok(validateProblemRecord(generic, genericCatalog).some((error) => error.startsWith('$.topics:')));
  generic.topics = ['integrals'];
  generic.prerequisites = [];
  assert.ok(validateProblemRecord(generic, genericCatalog).some((error) => error.startsWith('$.prerequisites:')));
});

test('catalog membership and every identity segment are mandatory and safe', () => {
  for (const [field, value] of [
    ['collectionId', 'unknown'],
    ['setId', '../2025'],
    ['problemId', 'A/1'],
  ]) {
    const record = structuredClone(validProblem);
    record[field] = value;
    assert.ok(validateProblem(record).some((error) => error.startsWith(`$.${field}:`)), field);
  }
});

test('figures cannot refer to remote or escaping paths', () => {
  const remoteFigure = structuredClone(validProblem);
  remoteFigure.statement.blocks.push({
    type: 'figure',
    path: 'https://example.com/diagram.svg',
    alt: 'A remote diagram',
  });

  const escapingFigure = structuredClone(validProblem);
  escapingFigure.statement.blocks.push({
    type: 'figure',
    path: '../shared/diagram.svg',
    alt: 'An escaping diagram',
  });

  assert.ok(validateProblem(remoteFigure).some((error) => error.includes('$.statement.blocks[3].path')));
  assert.ok(validateProblem(escapingFigure).some((error) => error.includes('$.statement.blocks[3].path')));
});

test('invalid TeX reports the exact JSON path', () => {
  const invalidMath = structuredClone(validProblem);
  invalidMath.hints[1].document.blocks[0].runs[1].tex = String.raw`\frac{1}{`;

  const errors = validateProblem(invalidMath);

  assert.ok(
    errors.some((error) => error.includes('$.hints[1].document.blocks[0].runs[1].tex')),
    errors.join('\n'),
  );
});

test('nested list and figure-caption math use the same TeX validation', () => {
  const invalidMath = structuredClone(validProblem);
  invalidMath.hints[3].document.blocks[0].items[1].push({
    type: 'inlineMath',
    tex: String.raw`\sqrt{`,
  });
  invalidMath.prerequisites[0].document.blocks.push({
    type: 'figure',
    path: 'figures/example.svg',
    alt: 'Example diagram',
    caption: [{ type: 'inlineMath', tex: String.raw`\frac{1}{` }],
  });

  const errors = validateProblem(invalidMath);

  assert.ok(
    errors.some((error) => error.includes('$.hints[3].document.blocks[0].items[1][1].tex')),
    errors.join('\n'),
  );
  assert.ok(
    errors.some((error) => error.includes('$.prerequisites[0].document.blocks[1].caption[0].tex')),
    errors.join('\n'),
  );
});

test('raw math in a fourth-hint list reports the exact text run path', () => {
  const rawMath = structuredClone(validProblem);
  rawMath.hints[3].document.blocks[0].items[1].push({ type: 'text', text: 'c_{n,k}' });

  assert.deepEqual(validateProblem(rawMath), [
    '$.hints[3].document.blocks[0].items[1][1].text: mathematical notation in list text must use inlineMath (math-script)',
  ]);
});

test('unicode comparison math in a list reports the exact text run path', () => {
  const rawMath = structuredClone(validProblem);
  rawMath.hints[3].document.blocks[0].items[1].push({ type: 'text', text: 'r≥n' });

  assert.deepEqual(validateProblem(rawMath), [
    '$.hints[3].document.blocks[0].items[1][1].text: mathematical notation in list text must use inlineMath (symbolic-relation)',
  ]);
});

test('the same list expression is accepted as an inlineMath run', () => {
  const inlineMath = structuredClone(validProblem);
  inlineMath.hints[3].document.blocks[0].items[1].push({ type: 'inlineMath', tex: 'c_{n,k}' });

  assert.deepEqual(validateProblem(inlineMath), []);
});

test('schema-invalid list runs produce schema errors without a raw-math diagnostic', () => {
  const invalidMath = structuredClone(validProblem);
  invalidMath.hints[3].document.blocks[0].items[1].push({ type: 'text', text: 42 });

  const errors = validateProblem(invalidMath);
  assert.ok(errors.some((error) => error.startsWith('$.hints[3].document.blocks[0].items[1][1].text:')));
  assert.equal(errors.some((error) => error.includes('mathematical notation in list text')), false);
});

test('unsafe TeX is rejected from every rich-document location', () => {
  const unsafeMath = structuredClone(validProblem);
  unsafeMath.solution.blocks[0].runs[1].tex = String.raw`\href{https://example.com}{x}`;

  const errors = validateProblem(unsafeMath);

  assert.ok(
    errors.some((error) => error.includes('$.solution.blocks[0].runs[1].tex: contains a command')),
    errors.join('\n'),
  );
});

test('one validation run returns independent structural and math errors', () => {
  const invalidProblem = structuredClone(validProblem);
  invalidProblem.hints = invalidProblem.hints.slice(0, 3);
  invalidProblem.solution.blocks[1].tex = String.raw`\sqrt{`;

  const errors = validateProblem(invalidProblem);

  assert.ok(errors.some((error) => error.includes('$.hints')));
  assert.ok(errors.some((error) => error.includes('$.solution.blocks[1].tex')));
});

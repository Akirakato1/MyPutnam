import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { SCHEMA_VERSION } from '../src/content-types.mjs';
import { validateProblem } from '../src/validate-problem.mjs';

const fixtureUrl = new URL('./fixtures/valid-problem.json', import.meta.url);
const validProblem = JSON.parse(await readFile(fixtureUrl, 'utf8'));

test('a complete problem record passes validation', () => {
  assert.equal(SCHEMA_VERSION, 2);
  assert.equal(validProblem.schemaVersion, 2);
  assert.equal('originalPdf' in validProblem, false);
  assert.deepEqual(validProblem.hints.map(({ stage }) => stage), [
    'nudge',
    'strategy',
    'knowledge',
    'outline',
  ]);
  assert.deepEqual(validateProblem(validProblem), []);
});

test('the canonical problem template is a valid schema-v2 record', async () => {
  const template = JSON.parse(
    await readFile(new URL('../templates/problem.template.json', import.meta.url), 'utf8'),
  );

  assert.equal(template.schemaVersion, 2);
  assert.deepEqual(validateProblem(template), []);
});

test('schema v1 and missing hint stages are rejected at exact paths', () => {
  const schemaV1 = structuredClone(validProblem);
  schemaV1.schemaVersion = 1;
  const missingStage = structuredClone(validProblem);
  delete missingStage.hints[0].stage;

  assert.ok(
    validateProblem(schemaV1).some((error) => error.startsWith('$.schemaVersion:')),
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

test('originalPdf is not part of schema v2', () => {
  const withOriginalPdf = structuredClone(validProblem);
  withOriginalPdf.originalPdf = { path: 'original/problems.pdf', pageIndex: 0 };

  const errors = validateProblem(withOriginalPdf);

  assert.ok(errors.some((error) => error.startsWith('$.originalPdf:')), errors.join('\n'));
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

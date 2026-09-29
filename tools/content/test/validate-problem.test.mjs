import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { validateProblem } from '../src/validate-problem.mjs';

const fixtureUrl = new URL('./fixtures/valid-problem.json', import.meta.url);
const validProblem = JSON.parse(await readFile(fixtureUrl, 'utf8'));

test('a complete problem record passes validation', () => {
  assert.deepEqual(validateProblem(validProblem), []);
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

test('one validation run returns independent structural and math errors', () => {
  const invalidProblem = structuredClone(validProblem);
  invalidProblem.hints = invalidProblem.hints.slice(0, 3);
  invalidProblem.solution.blocks[1].tex = String.raw`\sqrt{`;

  const errors = validateProblem(invalidProblem);

  assert.ok(errors.some((error) => error.includes('$.hints')));
  assert.ok(errors.some((error) => error.includes('$.solution.blocks[1].tex')));
});

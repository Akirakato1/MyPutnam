import assert from 'node:assert/strict';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';

import { auditYear } from '../src/audit-year.mjs';
import {
  createYearFixture,
  readProblem,
  writeProblem,
} from './helpers/year-fixture.mjs';

test('a complete twelve-problem year with review evidence passes', async (t) => {
  const { sourceRoot } = await createYearFixture(t);
  assert.deepEqual(await auditYear(sourceRoot, 2024), []);
});

test('missing codes and extra JSON files are both reported', async (t) => {
  const { sourceRoot } = await createYearFixture(t);
  await rm(join(sourceRoot, 'problems', 'A1.json'));
  await writeFile(join(sourceRoot, 'problems', 'notes.json'), '{}\n', 'utf8');

  const errors = await auditYear(sourceRoot, 2024);
  assert.ok(errors.some((error) => error.includes('problems/A1.json') && error.includes('missing')), errors.join('\n'));
  assert.ok(errors.some((error) => error.includes('problems/notes.json') && error.includes('unexpected')), errors.join('\n'));
});

test('wrong year and wrong ordinal are reported at their record paths', async (t) => {
  const { sourceRoot } = await createYearFixture(t);
  const A1 = await readProblem(sourceRoot, 'A1');
  A1.year = 2023;
  A1.ordinal = 2;
  await writeProblem(sourceRoot, 'A1', A1);

  const errors = await auditYear(sourceRoot, 2024);
  assert.ok(errors.some((error) => error.includes('problems/A1.json.year') && error.includes('2024')), errors.join('\n'));
  assert.ok(errors.some((error) => error.includes('problems/A1.json.ordinal') && error.includes('1')), errors.join('\n'));
});

test('fewer than four hints and missing prerequisites fail the year audit', async (t) => {
  const { sourceRoot } = await createYearFixture(t);
  const A2 = await readProblem(sourceRoot, 'A2');
  A2.hints.pop();
  A2.prerequisites = [];
  await writeProblem(sourceRoot, 'A2', A2);

  const errors = await auditYear(sourceRoot, 2024);
  assert.ok(errors.some((error) => error.includes('problems/A2.json.hints')), errors.join('\n'));
  assert.ok(errors.some((error) => error.includes('problems/A2.json.prerequisites') && error.includes('at least one')), errors.join('\n'));
});

test('a thin solution is rejected', async (t) => {
  const { sourceRoot } = await createYearFixture(t);
  const A3 = await readProblem(sourceRoot, 'A3');
  A3.solution.blocks = A3.solution.blocks.slice(0, 2);
  await writeProblem(sourceRoot, 'A3', A3);

  const errors = await auditYear(sourceRoot, 2024);
  assert.ok(errors.some((error) => error.includes('problems/A3.json.solution') && error.includes('three')), errors.join('\n'));
});

test('an absent review note and an incomplete review check are rejected', async (t) => {
  const { sourceRoot } = await createYearFixture(t);
  await rm(join(sourceRoot, 'review', 'A4.md'));
  const reviewPath = join(sourceRoot, 'review', 'A5.md');
  const review = await readFile(reviewPath, 'utf8');
  await writeFile(reviewPath, review.replace('Mathematical review: complete', 'Mathematical review: pending'), 'utf8');

  const errors = await auditYear(sourceRoot, 2024);
  assert.ok(errors.some((error) => error.includes('review/A4.md') && error.includes('missing')), errors.join('\n'));
  assert.ok(errors.some((error) => error.includes('review/A5.md') && error.includes('Mathematical review')), errors.join('\n'));
});

test('a record missing its solution citation is rejected', async (t) => {
  const { sourceRoot } = await createYearFixture(t);
  const B1 = await readProblem(sourceRoot, 'B1');
  B1.sources = B1.sources.filter(({ kind }) => kind !== 'solution');
  await writeProblem(sourceRoot, 'B1', B1);

  const errors = await auditYear(sourceRoot, 2024);
  assert.ok(errors.some((error) => error.includes('problems/B1.json.sources') && error.includes('solution')), errors.join('\n'));
});

test('a local figure must exist and have explicit review evidence', async (t) => {
  const { sourceRoot } = await createYearFixture(t);
  const B2 = await readProblem(sourceRoot, 'B2');
  B2.statement.blocks.push({
    type: 'figure',
    path: 'figures/diagram.svg',
    alt: 'A fixture diagram',
  });
  await writeProblem(sourceRoot, 'B2', B2);
  await mkdir(join(sourceRoot, 'problems', 'B2', 'figures'), { recursive: true });
  await writeFile(
    join(sourceRoot, 'problems', 'B2', 'figures', 'diagram.svg'),
    '<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"></svg>\n',
    'utf8',
  );

  const errors = await auditYear(sourceRoot, 2024);
  assert.ok(errors.some((error) => error.includes('review/B2.md') && error.includes('Figure review') && error.includes('figures/diagram.svg')), errors.join('\n'));
});

import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import test from 'node:test';

import { validateProblem } from '../src/validate-problem.mjs';

const yearRoot = new URL('../../../content-src/2025/', import.meta.url);
const requiredProblems = [
  { code: 'A1', ordinal: 1, problemPage: 'page 1', solutionPages: 'page 1' },
  { code: 'A2', ordinal: 2, problemPage: 'page 1', solutionPages: 'page 2' },
  { code: 'A3', ordinal: 3, problemPage: 'page 1', solutionPages: 'page 3' },
  { code: 'A4', ordinal: 4, problemPage: 'page 1', solutionPages: 'pages 4–5' },
  { code: 'A5', ordinal: 5, problemPage: 'page 1', solutionPages: 'page 6' },
  { code: 'A6', ordinal: 6, problemPage: 'page 1', solutionPages: 'pages 7–9' },
  { code: 'B1', ordinal: 7, problemPage: 'page 2', solutionPages: 'page 10' },
  { code: 'B2', ordinal: 8, problemPage: 'page 2', solutionPages: 'pages 11–12' },
  { code: 'B3', ordinal: 9, problemPage: 'page 2', solutionPages: 'page 13' },
  { code: 'B4', ordinal: 10, problemPage: 'page 2', solutionPages: 'pages 14–16' },
  { code: 'B5', ordinal: 11, problemPage: 'page 2', solutionPages: 'pages 17–18' },
  { code: 'B6', ordinal: 12, problemPage: 'page 2', solutionPages: 'page 19' },
];
const problemUrl = 'https://maa.org/wp-content/uploads/2026/02/2025-Putnam-Problems-for-Sessions-A-and-B.pdf';
const solutionUrl = 'https://maa.org/wp-content/uploads/2026/02/2025OfficialSolutions.pdf';

test('complete 2025 A1–A6 and B1–B6 records and review evidence are complete', async () => {
  assert.deepEqual(
    await readdir(new URL('problems/', yearRoot)),
    requiredProblems.map(({ code }) => `${code}.json`),
    'the complete 2025 set must contain exactly A1–A6 and B1–B6',
  );
  for (const expected of requiredProblems) {
    const record = await readJson(`problems/${expected.code}.json`);
    assert.deepEqual(validateProblem(record), [], `${expected.code} must satisfy the content schema`);
    assert.equal(record.year, 2025);
    assert.equal(record.schemaVersion, 2);
    assert.equal(record.code, expected.code);
    assert.equal(record.ordinal, expected.ordinal);
    assert.equal(record.hints.length, 4);
    assert.deepEqual(record.hints.map(({ order }) => order), [1, 2, 3, 4]);
    assert.deepEqual(record.hints.map(({ stage }) => stage), [
      'nudge',
      'strategy',
      'knowledge',
      'outline',
    ]);
    assert.ok(record.prerequisites.length > 0, `${expected.code} needs prerequisite refreshers`);
    assert.ok(record.solution.blocks.length >= 3, `${expected.code} needs a fully explained solution`);
    assert.equal(record.hints[3].document.blocks.some((block) => block.type === 'list'), true);
    assert.deepEqual(
      record.sources.map(({ kind, url }) => ({ kind, url })),
      [
        { kind: 'problem', url: problemUrl },
        { kind: 'solution', url: solutionUrl },
      ],
    );
    assert.equal('originalPdf' in record, false);

    const review = await readText(`review/${expected.code}.md`);
    assert.match(review, /^# 2025 Putnam [AB][1-6] content review$/m);
    assert.match(review, /- Statement fidelity: complete/i);
    assert.match(review, /- Mathematical review: complete/i);
    assert.match(review, /- Render review: complete/i);
    assert.match(
      review,
      new RegExp('- Problem source: `original/2025-problems\\.pdf`, ' + expected.problemPage + '\\.', 'i'),
    );
    assert.ok(
      review.includes(`- Solution source: \`original/2025-solutions.pdf\`, ${expected.solutionPages}.`),
      `${expected.code} must identify every official solution page reviewed`,
    );
  }
});

async function readJson(path) {
  return JSON.parse(await readText(path));
}

async function readText(path) {
  try {
    return await readFile(new URL(path, yearRoot), 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') {
      assert.fail(`Required 2025 content file is missing: ${path}`);
    }
    throw error;
  }
}

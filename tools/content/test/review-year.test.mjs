import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { access, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { reviewYear } from '../src/review-year.mjs';
import { createYearFixture } from './helpers/year-fixture.mjs';
import { reviewNote, YEAR_CODES } from './helpers/year-fixture.mjs';

test('local review writes only beneath caller-supplied preview and review roots', async (t) => {
  const { fixtureRoot, sourceRoot } = await createYearFixture(t);
  const previewRoot = join(fixtureRoot, 'preview');
  const reviewRoot = join(fixtureRoot, 'review-output');
  const sentinel = join(fixtureRoot, 'sentinel.txt');
  await writeFile(sentinel, 'keep', 'utf8');

  const result = await reviewYear({
    year: 2024,
    sourceRoot,
    previewRoot,
    reviewRoot,
    problems: ['A1'],
  });

  assert.equal(result.manifest.problems.length, 1);
  assert.equal(await readFile(sentinel, 'utf8'), 'keep');
  await access(join(previewRoot, 'content', 'putnam', '2024', 'manifest.json'));
  assert.ok((await readdir(reviewRoot)).some((name) => name.endsWith('.png')));
  assert.deepEqual(result.review.widths, [480, 720]);
  assert.equal(result.review.screenshots.length > result.review.files.length, true);
  assert.deepEqual(
    (await readdir(fixtureRoot)).sort(),
    ['preview', 'review-output', 'sentinel.txt', 'source'],
  );
});

test('preview mode renders an incomplete explicit subset at both required widths', async (t) => {
  const { fixtureRoot, sourceRoot } = await createYearFixture(t);
  for (const code of YEAR_CODES.slice(3)) {
    await rm(join(sourceRoot, 'problems', code + '.json'));
    await rm(join(sourceRoot, 'review', code + '.md'));
  }
  for (const code of YEAR_CODES.slice(0, 3)) {
    await writeFile(
      join(sourceRoot, 'review', code + '.md'),
      reviewNote(2024, code).replace('Render review: complete', 'Render review: pending'),
      'utf8',
    );
  }

  const result = await reviewYear({
    year: 2024,
    sourceRoot,
    previewRoot: join(fixtureRoot, 'preview-subset'),
    reviewRoot: join(fixtureRoot, 'review-subset'),
    problems: ['A1', 'A2', 'A3'],
    widths: [480, 720],
    requireCompleteYear: false,
    requireRenderReview: false,
  });

  assert.deepEqual(result.manifest.problems.map(({ problemId }) => problemId), ['A1', 'A2', 'A3']);
  assert.deepEqual(result.review.widths, [480, 720]);
  assert.equal(result.review.screenshots.length, result.review.files.length * 2);
});

test('the review CLI refuses preview or review output beneath app', async (t) => {
  const { fixtureRoot, sourceRoot } = await createYearFixture(t);
  const repositoryRoot = fileURLToPath(new URL('../../..', import.meta.url));
  const unsafeRoot = join(repositoryRoot, 'app', 'task2-unsafe-' + process.pid);
  const script = fileURLToPath(new URL('../src/review-year.mjs', import.meta.url));

  const result = spawnSync(process.execPath, [
    script,
    '--year', '2024',
    '--source', sourceRoot,
    '--preview-output', unsafeRoot,
    '--review-output', join(fixtureRoot, 'review-output'),
    '--problems', 'A1',
  ], {
    cwd: repositoryRoot,
    encoding: 'utf8',
  });

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /refusing.*app/i);
  await assert.rejects(access(unsafeRoot), { code: 'ENOENT' });
});

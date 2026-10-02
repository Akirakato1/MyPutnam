import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { access, readFile, readdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { reviewYear } from '../src/review-year.mjs';
import { createYearFixture } from './helpers/year-fixture.mjs';

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
  await access(join(previewRoot, 'manifest.json'));
  assert.ok((await readdir(reviewRoot)).some((name) => name.endsWith('.png')));
  assert.deepEqual(
    (await readdir(fixtureRoot)).sort(),
    ['preview', 'review-output', 'sentinel.txt', 'source'],
  );
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

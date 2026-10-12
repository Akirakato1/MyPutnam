import assert from 'node:assert/strict';
import { access, mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { renderReview } from '../src/render-review.mjs';

test('render review produces a deterministic document-by-width screenshot matrix and index', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'putnam-render-review-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const content = join(root, 'content-root');
  const documentRoot = join(content, 'content', 'putnam', '2024', 'problems', 'A1');
  const screenshots = join(root, 'screenshots');
  await mkdir(documentRoot, { recursive: true });
  await writeFile(join(documentRoot, 'statement.html'), '<!doctype html><meta charset="utf-8"><p>Rendered statement</p>', 'utf8');

  const result = await renderReview(content, screenshots, { widths: [480, 720] });

  assert.deepEqual(result.files, ['content/putnam/2024/problems/A1/statement.html']);
  assert.deepEqual(result.widths, [480, 720]);
  assert.equal(result.screenshots.length, 2);
  for (const screenshot of result.screenshots) await access(join(screenshots, screenshot));
  const index = JSON.parse(await readFile(join(screenshots, 'review-index.json'), 'utf8'));
  assert.deepEqual(index.entries.map(({ problemKey, width }) => [problemKey, width]), [
    ['putnam/2024/A1', 480],
    ['putnam/2024/A1', 720],
  ]);
});

test('render review rejects external requests', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'putnam-render-external-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const content = join(root, 'content-root');
  await mkdir(content, { recursive: true });
  await writeFile(
    join(content, 'external.html'),
    '<!doctype html><img src="https://example.invalid/external.png" alt="external">',
    'utf8',
  );

  await assert.rejects(
    renderReview(content, join(root, 'screenshots'), { widths: [480] }),
    /external request/i,
  );
});

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

test('render review rejects content clipped inside an overflow-hidden container', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'putnam-render-clipped-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const content = join(root, 'content-root');
  await mkdir(content, { recursive: true });
  await writeFile(
    join(content, 'clipped.html'),
    '<!doctype html><style>body{overflow:hidden}.clip{overflow:hidden;width:100%}.wide{width:2000px}</style><div class="clip"><div class="wide">Clipped formula</div></div>',
    'utf8',
  );

  await assert.rejects(
    renderReview(content, join(root, 'screenshots'), { widths: [480] }),
    /element exceeds viewport/i,
  );
});

test('render review ignores KaTeX radical-tail SVG coordinates inside its fitted slice', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'putnam-render-svg-coordinates-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const content = join(root, 'content-root');
  await mkdir(content, { recursive: true });
  await writeFile(
    join(content, 'svg.html'),
    '<!doctype html><style>.hide-tail{display:inline-block;overflow:hidden;width:24px;height:24px}.hide-tail svg{width:400em;height:24px}</style><p>Square root <span class="katex-html"><span class="hide-tail"><svg viewBox="0 0 400000 1080" preserveAspectRatio="xMinYMin slice"><path d="M0 0 H400000 V1080 H0 Z"/></svg></span></span></p>',
    'utf8',
  );

  const result = await renderReview(content, join(root, 'screenshots'), { widths: [480] });

  assert.deepEqual(result.files, ['svg.html']);
});

test('render review still rejects clipped SVG outside KaTeX output', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'putnam-render-clipped-svg-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const content = join(root, 'content-root');
  await mkdir(content, { recursive: true });
  await writeFile(
    join(content, 'clipped-svg.html'),
    '<!doctype html><style>.clip{overflow:hidden;width:100%}.clip svg{width:2000px;height:24px}</style><p>Diagram</p><div class="clip"><svg><path d="M0 0 H2000"/></svg></div>',
    'utf8',
  );

  await assert.rejects(
    renderReview(content, join(root, 'screenshots'), { widths: [480] }),
    /element exceeds viewport/i,
  );
});

test('render review rejects unrelated clipped SVG inside KaTeX output', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'putnam-render-katex-clipped-svg-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const content = join(root, 'content-root');
  await mkdir(content, { recursive: true });
  await writeFile(
    join(content, 'katex-clipped-svg.html'),
    '<!doctype html><style>.katex-html,.clip{display:block;width:100%}.clip{overflow:hidden}.clip svg{width:2000px;height:24px}</style><p>Formula</p><span class="katex-html"><span class="clip"><svg><path d="M0 0 H2000"/></svg></span></span>',
    'utf8',
  );

  await assert.rejects(
    renderReview(content, join(root, 'screenshots'), { widths: [480] }),
    /element exceeds viewport/i,
  );
});

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import test from 'node:test';

import { buildYear } from '../src/build-year.mjs';
import { renderDocument } from '../src/render-document.mjs';
import { renderReview } from '../src/render-review.mjs';

const fixtureUrl = new URL('./fixtures/valid-problem.json', import.meta.url);

test('renderer escapes prose and emits script-free HTML with a restrictive CSP', () => {
  const html = renderDocument({
    blocks: [
      {
        type: 'paragraph',
        runs: [
          { type: 'text', text: '<script>alert("unsafe")</script>' },
          { type: 'inlineMath', tex: String.raw`x<y` },
        ],
      },
      { type: 'displayMath', tex: String.raw`\sum_{k=1}^{n} k` },
    ],
  });

  assert.match(html, /&lt;script&gt;alert\(&quot;unsafe&quot;\)&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script(?:\s|>)/i);
  assert.match(html, /<meta http-equiv="Content-Security-Policy"/);
  assert.match(html, /default-src 'none'/);
  assert.match(html, /object-src 'none'/);
  assert.match(html, /class="katex/);
});

test('renderer supports every permitted block without allowing raw HTML', () => {
  const html = renderDocument({
    blocks: [
      { type: 'heading', level: 2, runs: [{ type: 'text', text: 'Key <idea>' }] },
      {
        type: 'list',
        ordered: false,
        items: [[{ type: 'text', text: 'First' }], [{ type: 'inlineMath', tex: 'x^2' }]],
      },
      {
        type: 'figure',
        path: 'assets/figures/unit-square.svg',
        alt: 'A <unit> square',
        caption: [{ type: 'text', text: 'A safe caption' }],
      },
    ],
  });

  assert.match(html, /<h2>Key &lt;idea&gt;<\/h2>/);
  assert.match(html, /<ul>/);
  assert.match(html, /src="assets\/figures\/unit-square.svg"/);
  assert.match(html, /alt="A &lt;unit&gt; square"/);
});

test('year builds are byte-for-byte deterministic', async () => {
  const root = await mkdtemp(join(tmpdir(), 'putnam-content-'));
  const sourceDir = join(root, 'source');
  const problemsDir = join(sourceDir, 'problems');
  const originalDir = join(sourceDir, 'original');
  const firstOutput = join(root, 'first');
  const secondOutput = join(root, 'second');

  await mkdir(problemsDir, { recursive: true });
  await mkdir(originalDir, { recursive: true });
  const problem = JSON.parse(await readFile(fixtureUrl, 'utf8'));
  problem.statement.blocks.push({
    type: 'figure',
    path: 'figures/unit-square.svg',
    alt: 'A unit square',
  });
  await mkdir(join(problemsDir, 'A1', 'figures'), { recursive: true });
  await writeFile(join(problemsDir, 'A1.json'), `${JSON.stringify(problem, null, 2)}\n`);
  await writeFile(
    join(problemsDir, 'A1', 'figures', 'unit-square.svg'),
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path d="M1 1h8v8H1z"/></svg>\n',
  );
  await writeFile(join(originalDir, '2025-problems.pdf'), '%PDF-1.4\n% deterministic fixture\n');

  const firstManifest = await buildYear(sourceDir, firstOutput);
  const secondManifest = await buildYear(sourceDir, secondOutput);

  assert.deepEqual(firstManifest, secondManifest);
  assert.deepEqual(await hashTree(firstOutput), await hashTree(secondOutput));

  const review = await renderReview(firstOutput, join(root, 'review-screenshots'));
  assert.equal(review.files.length, 7);
});

async function hashTree(root) {
  const files = [];
  await visit(root, root, files);
  return files;
}

async function visit(root, directory, files) {
  const entries = await readdir(directory, { withFileTypes: true });
  entries.sort((left, right) => left.name.localeCompare(right.name));

  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      await visit(root, path, files);
      continue;
    }

    const bytes = await readFile(path);
    files.push({
      path: relative(root, path).replaceAll('\\', '/'),
      sha256: createHash('sha256').update(bytes).digest('hex'),
    });
  }
}

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import test from 'node:test';

import { chromium } from 'playwright';

import { buildYear } from '../src/build-year.mjs';
import {
  auditRenderedDocument,
  findDocumentMathRuns,
  renderDocument,
  renderDocumentFragment,
} from '../src/render-document.mjs';
import { renderReview } from '../src/render-review.mjs';

const fixtureUrl = new URL('./fixtures/valid-problem.json', import.meta.url);

test('body fragments are inert, shell-free, and preserve every math root', () => {
  const document = {
    blocks: [
      { type: 'paragraph', runs: [
        { type: 'text', text: '<script src="https://bad.test/x.js">bad</script> ' },
        { type: 'inlineMath', tex: 'x^2' },
      ] },
      { type: 'displayMath', tex: 'x^2+y^2=1' },
      { type: 'list', ordered: true, items: [[
        { type: 'text', text: 'Use ' }, { type: 'inlineMath', tex: '\\pi' },
      ]] },
    ],
  };
  const fragment = renderDocumentFragment(document, { richDocumentVersion: 1 });
  assert.doesNotMatch(fragment, /<(?:html|head|body|script|style|base|frame|iframe|form|object|embed)\b/i);
  assert.doesNotMatch(fragment, /\son[a-z]+\s*=|(?:src|href)\s*=\s*["'](?:https?:|data:|\/\/)/i);
  assert.match(fragment, /&lt;script src=&quot;https:\/\/bad\.test\/x\.js&quot;&gt;/);
  assert.equal((fragment.match(/class="katex"/g) ?? []).length, 3);
});

test('rich-document v2 tables emit semantic scroll-local markup and cell math', () => {
  const document = {
    blocks: [{
      type: 'table',
      caption: [{ type: 'text', text: 'Transform <pairs>' }],
      headers: [
        [{ type: 'text', text: 'Function' }],
        [{ type: 'text', text: 'Transform ' }, { type: 'inlineMath', tex: 'F(s)' }],
      ],
      rows: [[
        { blocks: [{ type: 'paragraph', runs: [{ type: 'inlineMath', tex: '1' }] }] },
        { blocks: [{ type: 'displayMath', tex: '1/s' }] },
      ]],
    }],
  };
  const fragment = renderDocumentFragment(document, { richDocumentVersion: 2 });
  assert.match(fragment, /^<div class="table-scroll"><table>/);
  assert.match(fragment, /<caption>Transform &lt;pairs&gt;<\/caption>/);
  assert.equal((fragment.match(/<th scope="col">/g) ?? []).length, 2);
  assert.equal((fragment.match(/class="katex"/g) ?? []).length, 3);
  assert.doesNotMatch(fragment, /<(?:html|head|style|script)\b/i);
});

test('legacy full pages wrap the same fragment renderer', () => {
  const document = { blocks: [{ type: 'paragraph', runs: [{ type: 'text', text: 'Shared body.' }] }] };
  const fragment = renderDocumentFragment(document, { richDocumentVersion: 1 });
  const page = renderDocument(document, { richDocumentVersion: 1 });
  assert.ok(page.includes(`<main class="document">${fragment}</main>`));
});

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
  }, { sharedAssetPrefix: '../../../../shared', title: 'Course <One>' });

  assert.match(html, /&lt;script&gt;alert\(&quot;unsafe&quot;\)&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script(?:\s|>)/i);
  assert.match(html, /<meta http-equiv="Content-Security-Policy"/);
  assert.match(html, /default-src 'none'/);
  assert.match(html, /object-src 'none'/);
  assert.match(html, /class="katex/);
  assert.match(html, /\.\.\/\.\.\/\.\.\/\.\.\/shared\/content\.css/);
  assert.match(html, /<title>Course &lt;One&gt;<\/title>/);
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

test('ordered and unordered list items keep mixed prose and multiple KaTeX roots', async () => {
  const html = renderDocument({
    blocks: [
      {
        type: 'list', ordered: true,
        items: [
          [
            { type: 'text', text: 'Compare <coefficients> & "powers": ' },
            { type: 'inlineMath', tex: 'c_{n,k}' },
            { type: 'text', text: ' with ' },
            { type: 'inlineMath', tex: '2^{k+1}' },
            { type: 'text', text: '.' },
          ],
          [{ type: 'text', text: 'Keep the next step in its own item.' }],
        ],
      },
      {
        type: 'list', ordered: false,
        items: [[
          { type: 'text', text: "Check the matrices' relation " },
          { type: 'inlineMath', tex: 'A_iA_j=A_jA_i' },
          { type: 'text', text: ' in ' },
          { type: 'inlineMath', tex: String.raw`\mathbb R^3` },
          { type: 'text', text: '.' },
        ]],
      },
    ],
  });
  assert.match(html, /Compare &lt;coefficients&gt; &amp; &quot;powers&quot;: /);
  assert.match(html, /matrices&#39; relation /);

  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    await page.setContent(html);
    const lists = await page.locator('.document > ol, .document > ul').evaluateAll((elements) =>
      elements.map((list) => ({
        tag: list.tagName,
        children: [...list.children].map((item) => {
          const prose = item.cloneNode(true);
          prose.querySelectorAll('.katex').forEach((math) => math.remove());
          return {
            tag: item.tagName,
            roots: item.querySelectorAll('.katex').length,
            formulas: [...item.querySelectorAll('.katex annotation')].map((node) => node.textContent),
            prose: prose.textContent,
          };
        }),
      })));
    assert.deepEqual(lists, [
      { tag: 'OL', children: [
        { tag: 'LI', roots: 2, formulas: ['c_{n,k}', '2^{k+1}'],
          prose: 'Compare <coefficients> & "powers":  with .' },
        { tag: 'LI', roots: 0, formulas: [], prose: 'Keep the next step in its own item.' },
      ] },
      { tag: 'UL', children: [
        { tag: 'LI', roots: 2, formulas: ['A_iA_j=A_jA_i', String.raw`\mathbb R^3`],
          prose: "Check the matrices' relation  in ." },
      ] },
    ]);
    const outsideAnnotations = await page.locator('.document').evaluate((element) => {
      const copy = element.cloneNode(true);
      copy.querySelectorAll('annotation').forEach((node) => node.remove());
      return copy.textContent;
    });
    assert.doesNotMatch(outsideAnnotations, /c_\{n,k\}|A_iA_j=A_jA_i/);
    assert.equal(await page.locator('.document .katex').count(), 4);
  } finally {
    await browser.close();
  }
});

test('math audit covers nested runs and permits documents with no math', () => {
  const nestedMath = {
    blocks: [
      {
        type: 'list',
        ordered: true,
        items: [[{ type: 'text', text: 'Use ' }, { type: 'inlineMath', tex: 'x^2' }]],
      },
      {
        type: 'figure',
        path: 'figures/example.svg',
        alt: 'Example',
        caption: [{ type: 'inlineMath', tex: 'y=1' }],
      },
      { type: 'displayMath', tex: 'x^2+y^2=1' },
    ],
  };
  const noMath = {
    blocks: [{ type: 'paragraph', runs: [{ type: 'text', text: 'No formulas here.' }] }],
  };

  assert.deepEqual(
    findDocumentMathRuns(nestedMath).map(({ path }) => path),
    [
      '$.blocks[0].items[0][1].tex',
      '$.blocks[1].caption[0].tex',
      '$.blocks[2].tex',
    ],
  );
  assert.deepEqual(auditRenderedDocument(nestedMath, renderDocument(nestedMath)), {
    mathRunCount: 3,
    katexRootCount: 3,
  });
  assert.deepEqual(auditRenderedDocument(noMath, renderDocument(noMath)), {
    mathRunCount: 0,
    katexRootCount: 0,
  });
  assert.throws(
    () => auditRenderedDocument(nestedMath, '<html><body>missing math</body></html>'),
    /expected 3 KaTeX root/,
  );
});

test('year builds are byte-for-byte deterministic', async () => {
  const root = await mkdtemp(join(tmpdir(), 'putnam-content-'));
  const sourceDir = join(root, 'source');
  const problemsDir = join(sourceDir, 'problems');
  const firstOutput = join(root, 'first');
  const secondOutput = join(root, 'second');

  await mkdir(problemsDir, { recursive: true });
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

  const firstManifest = await buildYear(sourceDir, firstOutput);
  const secondManifest = await buildYear(sourceDir, secondOutput);

  assert.deepEqual(firstManifest, secondManifest);
  assert.deepEqual(await hashTree(firstOutput), await hashTree(secondOutput));
  const figureAsset = firstManifest.assets.find(({ path }) => path.endsWith('/figures/unit-square.svg'));
  assert.ok(figureAsset, 'referenced local figures must be copied and hashed');
  assert.match(figureAsset.sha256, /^[a-f0-9]{64}$/);
  assert.equal(firstManifest.assets.some(({ path }) => path.endsWith('.pdf')), false);

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

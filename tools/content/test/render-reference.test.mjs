import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { renderReferenceArticle } from '../src/render-reference.mjs';

const fixtureUrl = new URL('./fixtures/contracts/valid-reference.json', import.meta.url);

test('reference renderer uses the approved section order and semantic table markup', async () => {
  const record = JSON.parse(await readFile(fixtureUrl, 'utf8'));
  record.results.blocks.push({
    type: 'table',
    caption: [{ type: 'text', text: 'Small values' }],
    headers: [
      [{ type: 'inlineMath', tex: 'n' }],
      [{ type: 'inlineMath', tex: '\\varphi(n)' }],
    ],
    rows: [[
      { blocks: [{ type: 'paragraph', runs: [{ type: 'text', text: '6' }] }] },
      { blocks: [{ type: 'paragraph', runs: [{ type: 'text', text: '2' }] }] },
    ]],
  });

  const html = renderReferenceArticle(record);
  const markers = [
    '<h1>Euler&#39;s totient function</h1>', 'Aliases', 'Notation', 'Definitions',
    'Results and methods', 'Conditions and conventions', 'Worked examples',
    'Pitfalls and limitations', 'Related references', 'Related problems', 'Attribution',
  ];
  let previous = -1;
  for (const marker of markers) {
    const index = html.indexOf(marker);
    assert.ok(index > previous, `${marker} must appear in fixed order`);
    previous = index;
  }
  assert.match(html, /<div class="table-scroll"><table>/);
  assert.match(html, /<caption>Small values<\/caption>/);
  assert.match(html, /<th scope="col">/);
  assert.match(html, /<td><p>6<\/p><\/td>/);
  assert.equal((html.match(/class="katex"/g) ?? []).length, 5);
  assert.doesNotMatch(html, /<(?:html|head|body|script|style|iframe)\b/i);
  assert.doesNotMatch(html, /https:\/\//i);
});

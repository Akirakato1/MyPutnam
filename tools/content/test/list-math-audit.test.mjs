import assert from 'node:assert/strict';
import test from 'node:test';

import { findRawMathInListText } from '../src/list-math-audit.mjs';

function listDocument(text) {
  return { blocks: [{ type: 'list', ordered: true, items: [[{ type: 'text', text }]] }] };
}

for (const [text, rule] of [
  ['c_{n,k}', 'math-script'],
  ['2^{k+1}', 'math-script'],
  ['A_iA_j=A_jA_i', 'math-script'],
  ['R^3', 'math-script'],
  ['x_2-x_1', 'math-script'],
  ['a+b<3^a5^b', 'math-script'],
  ["N=N'+r", 'symbolic-relation'],
  ['x < 3', 'symbolic-relation'],
  ['n > k', 'symbolic-relation'],
  [String.raw`Use \binom{n}{k} and \alpha.`, 'tex-command'],
  [String.raw`Use x_2 = \alpha.`, 'tex-command'],
]) {
  test(`reports ${text} at the exact list text path as ${rule}`, () => {
    assert.deepEqual(findRawMathInListText(listDocument(text)), [{
      path: '$.blocks[0].items[0][0].text', text, rule,
    }]);
  });
}

test('ordinary prose, filenames, URLs, and escaped notation are safe', () => {
  for (const text of [
    "It's the reader's next step.",
    'Use the zero-product property and a well-chosen example.',
    'Read hint_list.md, x_2.txt, and notes-v2.json.',
    'Visit https://example.com/x_2?q=a=b or https://example.com/a^2.',
    'The result equals the bound and is less than the total.',
    'The symbols: <, >, =.',
    String.raw`Print \\alpha, x\_2, and R\^3 literally.`,
  ]) {
    assert.deepEqual(findRawMathInListText(listDocument(text)), [], text);
  }
});

test('an odd number of backslashes still leaves an unescaped TeX command', () => {
  const text = String.raw`Print \\\alpha.`;
  assert.deepEqual(findRawMathInListText(listDocument(text)), [{
    path: '$.blocks[0].items[0][0].text', text, rule: 'tex-command',
  }]);
});

test('only list text runs are audited, leaving other rich-document locations alone', () => {
  const document = { blocks: [
    { type: 'paragraph', runs: [{ type: 'text', text: 'x_2' }] },
    { type: 'heading', level: 1, runs: [{ type: 'text', text: 'x_2' }] },
    { type: 'displayMath', tex: 'x_2' },
    { type: 'figure', path: 'x_2.svg', caption: [{ type: 'text', text: 'x_2' }] },
    { type: 'list', ordered: false, items: [[{ type: 'inlineMath', tex: 'x_2' }]] },
  ] };
  assert.deepEqual(findRawMathInListText(document), []);
});

test('findings preserve block, item, and run order without duplicate hits', () => {
  const document = listDocument('x_2 and y_3');
  document.blocks[0].items[0].push({ type: 'text', text: 'n > 1' });
  document.blocks[0].items.push([{ type: 'text', text: 'R^3' }]);
  document.blocks.push(...listDocument('N=N\'+r').blocks);
  assert.deepEqual(findRawMathInListText(document, '$.hints[3].document'), [
    { path: '$.hints[3].document.blocks[0].items[0][0].text', text: 'x_2 and y_3', rule: 'math-script' },
    { path: '$.hints[3].document.blocks[0].items[0][1].text', text: 'n > 1', rule: 'symbolic-relation' },
    { path: '$.hints[3].document.blocks[0].items[1][0].text', text: 'R^3', rule: 'math-script' },
    { path: '$.hints[3].document.blocks[1].items[0][0].text', text: "N=N'+r", rule: 'symbolic-relation' },
  ]);
});

test('malformed document fragments are ignored safely', () => {
  for (const document of [null, {}, { blocks: null }, { blocks: [
    null, { type: 'list', items: null }, { type: 'list', items: [null, [{ type: 'text' }]] },
  ] }]) {
    assert.deepEqual(findRawMathInListText(document), []);
  }
});

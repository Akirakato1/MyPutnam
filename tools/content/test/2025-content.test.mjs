import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { auditYear } from '../src/audit-year.mjs';

const yearRoot = new URL('../../../content-src/2025/', import.meta.url);

test('the complete 2025 corpus passes the reusable year audit', async () => {
  assert.deepEqual(await auditYear(yearRoot, 2025), []);
});

const fourthHintMath = [
  ['A4', 'dimension bounds and matrix commutation', [
    ['k=1'], ['k=2'], ['\\mathbb R^3'], ['A_iA_j=A_jA_i'],
  ]],
  ['A5', 'the complemented sign pair', [
    ['(-1,-1)'], [], [], [],
  ]],
  ['A6', 'both congruence lemmas and the base index', [
    ['c_{n,k}', '2^{k+1}', '2^{k+2}'],
    ['d_{n,k}', '2^{2k+2}', '2^{2k+3}'],
    ['n=0'],
  ]],
  ['B1', 'point labels and the intersection-count contradiction', [
    ['R', 'R'], ['G', 'G'], ['6+6<18'],
  ]],
  ['B2', 'the indexed difference and both sign factors', [
    ['x_2-x_1'], ['x-y', 'f(x)-f(y)'], [],
  ]],
  ['B3', 'both exponent cases, size bounds, and the divisibility expression', [
    ['a=b=0', 'k<c\\le n'], ['a+b>0', 'm=(a+b)k'],
    ['m<n', 'a+b<3^a5^b', 'k<c'], ['n', '2025^m-15^m'],
  ]],
  ['B4', 'the count recurrences, induction size, and support bound', [
    ['r', "N=N'+r", "S=N+S'"], ['r\\ge n', '(n-1)'], ['n(n+1)/2'],
  ]],
  ['B5', 'the slot parameter, choice count, steps, and downstep bound', [
    ['t'], ['p-3', 't'], ['I(k)', 'I(k+1)', '(p-3)/4'],
  ]],
  ['B6', 'the growth inequalities and every shifted argument', [
    ['n', 'g(n)\\ge n+2', 'g(g(n))\\ge g(n+2)'],
    ['n', 'n+1', 'g(n+2)', 'g(n+1)', 'g(n+1)'], [],
  ]],
];

for (const [problemId, formulas, expectedItems] of fourthHintMath) {
  test(`${problemId} fourth-hint list renders ${formulas} as inline math`, async () => {
    const problem = JSON.parse(await readFile(
      new URL(`problems/${problemId}.json`, yearRoot), 'utf8',
    ));
    const hint = problem.hints[3];
    assert.equal(hint.order, 4);
    assert.equal(hint.stage, 'outline');
    assert.equal(hint.document.blocks[0].type, 'list');
    assert.equal(hint.document.blocks[0].ordered, true);
    assert.deepEqual(hint.document.blocks[0].items.map((runs) =>
      runs.filter(({ type }) => type === 'inlineMath').map(({ tex }) => tex)), expectedItems);
  });
}

test('every 2025 review records prerequisite and narrow/wide render evidence', async () => {
  for (const problemId of ['A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'B1', 'B2', 'B3', 'B4', 'B5', 'B6']) {
    const review = await readFile(new URL(`review/${problemId}.md`, yearRoot), 'utf8');
    assert.match(review, /Prerequisite review: complete/);
    assert.match(review, /Render review: complete/);
    assert.match(review, /480 CSS px/);
    assert.match(review, /720 CSS px/);
  }
});

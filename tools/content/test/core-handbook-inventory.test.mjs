import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';

import { auditReferenceInventory } from '../src/audit-reference-inventory.mjs';

const repositoryRoot = resolve(import.meta.dirname, '..', '..', '..');
const referenceRoot = join(repositoryRoot, 'content-src', 'reference');

const expected = [
  ['algebra-inequalities', ['algebraic-identities', 'exponent-logarithm-laws', 'polynomial-roots-vieta', 'binomial-multinomial-theorems', 'am-gm-and-power-means', 'cauchy-schwarz-holder-minkowski', 'jensen-convexity', 'rearrangement-chebyshev']],
  ['sequences-series-recurrences', ['arithmetic-geometric-sequences', 'finite-sums-and-telescoping', 'power-sums', 'infinite-series-tests', 'linear-recurrences-homogeneous', 'linear-recurrences-nonhomogeneous', 'ordinary-generating-functions']],
  ['trigonometry', ['angle-addition-identities', 'double-half-multiple-angle', 'product-sum-power-reduction', 'triangle-trigonometry', 'inverse-trig-conventions', 'harmonic-linear-combination', 'weierstrass-substitution']],
  ['geometry-vectors', ['coordinate-geometry', 'lines-circles-conics', 'triangle-area-centers', 'circle-power-radical-axis', 'dot-product-projections', 'cross-product-area-volume', 'vector-lines-planes']],
  ['complex-numbers', ['complex-algebra', 'modulus-argument', 'polar-form-de-moivre', 'complex-roots', 'roots-of-unity', 'complex-plane-geometry']],
  ['number-theory', ['divisibility-gcd-bezout', 'primes-factorization', 'modular-arithmetic', 'fermat-euler-theorems', 'euler-totient', 'chinese-remainder-theorem', 'multiplicative-order', 'p-adic-valuations-legendre', 'divisor-functions-diophantine']],
  ['combinatorics-generating-functions', ['permutations-combinations', 'binomial-identities', 'inclusion-exclusion', 'pigeonhole-extremal', 'double-counting', 'recurrence-counting', 'ordinary-generating-functions-combinatorics', 'exponential-generating-functions', 'catalan-numbers']],
  ['calculus', ['limits-continuity', 'derivative-rules-table', 'taylor-maclaurin', 'extrema-convexity', 'fundamental-theorem-calculus', 'antiderivative-table', 'substitution', 'integration-by-parts', 'partial-fractions', 'trig-integrals-substitutions', 'reduction-formulas', 'improper-integrals']],
  ['linear-algebra', ['matrices-linear-systems', 'determinants', 'vector-spaces-basis', 'rank-nullity', 'eigenvalues-diagonalization', 'inner-products-orthogonality', 'quadratic-forms']],
  ['probability', ['probability-counting', 'conditional-probability-bayes', 'discrete-distributions', 'continuous-distributions', 'expectation', 'variance-covariance', 'generating-functions-probability', 'probability-inequalities']],
  ['differential-equations', ['separable-equations', 'first-order-linear', 'exact-and-bernoulli', 'second-order-homogeneous', 'forced-second-order', 'variation-of-parameters', 'linear-systems']],
  ['laplace-fourier-transforms', ['laplace-definition-convergence', 'laplace-properties', 'laplace-transform-table', 'inverse-laplace', 'convolution-and-ode', 'fourier-series', 'fourier-transform-conventions', 'fourier-transform-properties', 'fourier-transform-table', 'convolution-parseval-distributions']],
];

test('core handbook freezes exactly 12 ordered categories and 97 ordered article keys', async () => {
  const categories = JSON.parse(await readFile(join(referenceRoot, 'categories.json'), 'utf8'));
  const inventory = JSON.parse(await readFile(join(referenceRoot, 'core-handbook-v1.json'), 'utf8'));

  assert.equal(categories.schemaVersion, 1);
  assert.equal(inventory.schemaVersion, 1);
  assert.equal(inventory.inventoryId, 'core-handbook-v1');
  assert.deepEqual(categories.categories.map(({ id }) => id), expected.map(([id]) => id));
  assert.deepEqual(
    inventory.categories.map(({ id, articles }) => [id, articles.map(({ id: articleId }) => articleId)]),
    expected,
  );
  const keys = inventory.categories.flatMap(({ id, articles }) =>
    articles.map(({ id: articleId }) => `math-reference/${id}/${articleId}`));
  assert.equal(keys.length, 97);
  assert.equal(new Set(keys).size, 97);

  const aliasesByKey = new Map(inventory.categories.flatMap(({ id, articles }) =>
    articles.map((article) => [`math-reference/${id}/${article.id}`, article.requiredAliases])));
  assert.deepEqual(aliasesByKey.get('math-reference/number-theory/euler-totient'), ['phi', 'Euler phi', 'totient']);
  assert.ok(aliasesByKey.get('math-reference/calculus/integration-by-parts').includes('integration by parts'));
  assert.ok(aliasesByKey.get('math-reference/trigonometry/weierstrass-substitution').includes('Weierstrass substitution'));
  assert.ok(aliasesByKey.get('math-reference/sequences-series-recurrences/linear-recurrences-homogeneous').includes('linear recurrence'));
  assert.ok(aliasesByKey.get('math-reference/laplace-fourier-transforms/laplace-transform-table').includes('Laplace table'));
  assert.ok(aliasesByKey.get('math-reference/laplace-fourier-transforms/fourier-transform-conventions').includes('Fourier transform'));
});

test('inventory audit accepts a complete approved rights-cleared article and review', async (t) => {
  const fixture = await fixtureTree(t);
  assert.deepEqual(await auditReferenceInventory(fixture), []);
});

test('inventory audit reports missing sections, relation closure, rights, and review disposition at exact paths', async (t) => {
  const fixture = await fixtureTree(t);
  const articlePath = join(fixture.sourceRoot, 'reference', 'articles', 'number-theory', 'euler-totient.json');
  const article = JSON.parse(await readFile(articlePath, 'utf8'));
  delete article.conditions;
  article.relatedReferences = ['math-reference/number-theory/not-in-inventory'];
  delete article.rights.licenseBasis;
  article.rights.redistributionStatus = 'unresolved';
  await writeFile(articlePath, JSON.stringify(article));
  const reviewPath = join(fixture.sourceRoot, 'reference', 'review', 'number-theory', 'euler-totient.md');
  await writeFile(reviewPath, approvedReview()
    .replace('## Render review\n- 336/512/1280: passed', '## Render review\n- 336/512/1280: passed\n\n## Extra')
    .replace('## Findings\n- None.', '## Findings\n- Formula condition remains unresolved.')
    .replace('## Disposition\napproved', '## Disposition\nchanges-required'));

  const errors = await auditReferenceInventory(fixture);
  assert.ok(errors.some((error) => error.startsWith('reference/articles/number-theory/euler-totient.json: $.conditions')));
  assert.ok(errors.includes('reference/articles/number-theory/euler-totient.json: $.relatedReferences[0] is outside the frozen inventory'));
  assert.ok(errors.some((error) => error.startsWith('reference/articles/number-theory/euler-totient.json: $.rights.licenseBasis')));
  assert.ok(errors.includes('reference/articles/number-theory/euler-totient.json: rights.redistributionStatus must be cleared'));
  assert.ok(errors.includes('reference/review/number-theory/euler-totient.md: unexpected heading Extra'));
  assert.ok(errors.includes('reference/review/number-theory/euler-totient.md: findings must be resolved before approval'));
  assert.ok(errors.includes('reference/review/number-theory/euler-totient.md: disposition changes-required is not publishable'));
});

test('inventory audit reports category/order drift, missing reviews, missing articles, and required aliases', async (t) => {
  const fixture = await fixtureTree(t);
  const categoriesPath = join(fixture.sourceRoot, 'reference', 'categories.json');
  const categories = JSON.parse(await readFile(categoriesPath, 'utf8'));
  categories.categories[0].displayOrder = 2;
  await writeFile(categoriesPath, JSON.stringify(categories));
  const articlePath = join(fixture.sourceRoot, 'reference', 'articles', 'number-theory', 'euler-totient.json');
  const article = JSON.parse(await readFile(articlePath, 'utf8'));
  article.aliases = ['totient'];
  await writeFile(articlePath, JSON.stringify(article));
  await rm(join(fixture.sourceRoot, 'reference', 'review'), { recursive: true, force: true });

  let errors = await auditReferenceInventory(fixture);
  assert.ok(errors.includes('reference/categories.json: categories[0].displayOrder must equal 1'));
  assert.ok(errors.includes('reference/articles/number-theory/euler-totient.json: missing required alias phi'));
  assert.ok(errors.includes('reference/review/number-theory/euler-totient.md: review record is missing'));

  await rm(articlePath);
  errors = await auditReferenceInventory(fixture);
  assert.ok(errors.includes('reference/articles/number-theory/euler-totient.json: article is missing'));
});

async function fixtureTree(t) {
  const sourceRoot = await mkdtemp(join(tmpdir(), 'myputnam-reference-inventory-'));
  t.after(() => rm(sourceRoot, { recursive: true, force: true }));
  const root = join(sourceRoot, 'reference');
  await mkdir(join(root, 'articles', 'number-theory'), { recursive: true });
  await mkdir(join(root, 'review', 'number-theory'), { recursive: true });
  const categories = {
    schemaVersion: 1,
    categories: [{ id: 'number-theory', label: 'Number Theory', displayOrder: 1, aliases: ['NT'] }],
  };
  const inventory = {
    schemaVersion: 1,
    inventoryId: 'test-handbook',
    categories: [{
      id: 'number-theory', label: 'Number Theory', displayOrder: 1,
      articles: [{ id: 'euler-totient', displayOrder: 1, requiredAliases: ['phi', 'totient'] }],
    }],
  };
  const article = JSON.parse(await readFile(new URL('./fixtures/contracts/valid-reference.json', import.meta.url), 'utf8'));
  article.relatedReferences = [];
  article.relatedProblems = [];
  await writeFile(join(root, 'categories.json'), JSON.stringify(categories));
  const inventoryPath = join(root, 'core-handbook-v1.json');
  await writeFile(inventoryPath, JSON.stringify(inventory));
  await writeFile(join(root, 'articles', 'number-theory', 'euler-totient.json'), JSON.stringify(article));
  await writeFile(join(root, 'review', 'number-theory', 'euler-totient.md'), approvedReview());
  return { sourceRoot, inventoryPath };
}

function approvedReview() {
  return `# Reference article review

## Identity
- Key: math-reference/number-theory/euler-totient

## Mathematical correctness
- Passed.

## Notation and conventions
- Passed.

## Worked example
- Passed.

## Search aliases
- Passed.

## Sources and rights
- Passed.

## Render review
- 336/512/1280: passed

## Findings
- None.

## Disposition
approved
`;
}

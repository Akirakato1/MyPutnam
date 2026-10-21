import assert from 'node:assert/strict';
import { cp, mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';

import { auditReferenceInventory } from '../src/audit-reference-inventory.mjs';
import { buildReferencePackage } from '../src/build-reference-package.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';
import { renderReferenceArticle } from '../src/render-reference.mjs';
import { renderReview } from '../src/render-review.mjs';
import { validateReferenceArticle } from '../src/validate-reference.mjs';

const repositoryRoot = resolve(import.meta.dirname, '..', '..', '..');
const sourceRoot = join(repositoryRoot, 'content-src');
const referenceRoot = join(sourceRoot, 'reference');
const categoryId = 'probability';
const expectedArticles = [
  ['probability-counting', 1, ['sample space', 'equally likely outcomes']],
  ['conditional-probability-bayes', 2, ['Bayes theorem', 'independence']],
  ['discrete-distributions', 3, ['Bernoulli distribution', 'binomial distribution']],
  ['continuous-distributions', 4, ['normal distribution', 'exponential distribution']],
  ['expectation', 5, ['expected value', 'indicator variables']],
  ['variance-covariance', 6, ['law of total variance', 'covariance']],
  ['generating-functions-probability', 7, ['probability generating function', 'moment generating function']],
  ['probability-inequalities', 8, ['Markov inequality', 'Chebyshev inequality']],
];

test('probability ships the exact approved eight-article batch with support and existence conventions', async () => {
  const inventory = JSON.parse(await readFile(join(referenceRoot, 'core-handbook-v1.json'), 'utf8'));
  const frozen = inventory.categories.find(({ id }) => id === categoryId);
  assert.deepEqual(frozen.articles.map(({ id, displayOrder }) => [id, displayOrder]), expectedArticles.map(([id, order]) => [id, order]));

  const libraryCatalog = await loadLibraryCatalog(join(sourceRoot, 'library.json'));
  const records = new Map();
  for (const [articleId, displayOrder, requiredSearchTerms] of expectedArticles) {
    const record = JSON.parse(await readFile(join(referenceRoot, 'articles', categoryId, articleId + '.json'), 'utf8'));
    records.set(articleId, record);
    assert.equal(record.categoryId, categoryId);
    assert.equal(record.articleId, articleId);
    assert.equal(record.displayOrder, displayOrder);
    assert.deepEqual(validateReferenceArticle(record, { categories: libraryCatalog.referenceCategories }), []);
    const searchable = [...record.aliases, ...record.keywords].map((value) => value.toLocaleLowerCase('en-US'));
    for (const term of requiredSearchTerms) assert.ok(searchable.includes(term.toLocaleLowerCase('en-US')), articleId + ' must be searchable by ' + term);
    assert.ok(record.examples.length >= 1);
    assert.equal(record.rights.redistributionStatus, 'cleared');
    const review = await readFile(join(referenceRoot, 'review', categoryId, articleId + '.md'), 'utf8');
    assert.match(review, /## Findings\r?\n- None\./);
    assert.match(review, /## Disposition\r?\napproved\s*$/);
  }

  assert.match(JSON.stringify(records.get('probability-counting').conditions), /equally likely/i);
  assert.match(JSON.stringify(records.get('conditional-probability-bayes').conditions), /nonzero/i);
  assert.match(JSON.stringify(records.get('discrete-distributions').conditions), /support/i);
  assert.match(JSON.stringify(records.get('continuous-distributions').conditions), /density/i);
  assert.match(JSON.stringify(records.get('expectation').conditions), /exists/i);
  assert.match(JSON.stringify(records.get('variance-covariance').conditions), /finite second moment/i);
  assert.match(JSON.stringify(records.get('generating-functions-probability').conditions), /neighborhood of zero/i);
  assert.match(JSON.stringify(records.get('probability-inequalities').conditions), /nonnegative/i);
  assert.deepEqual(await auditReferenceInventory({ sourceRoot, inventoryPath: join(referenceRoot, 'core-handbook-v1.json'), categoryIds: [categoryId] }), []);
});

test('probability packages are deterministic and render at all review widths', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'myputnam-probability-reference-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const contentRoot = join(root, 'content');
  const screenshotRoot = join(root, 'screenshots');
  const sharedRoot = join(contentRoot, 'shared');
  const assetRoot = join(root, 'assets');
  await mkdir(assetRoot, { recursive: true });
  await mkdir(sharedRoot, { recursive: true });
  await cp(join(repositoryRoot, 'tools', 'content', 'node_modules', 'katex', 'dist'), join(sharedRoot, 'katex'), { recursive: true });
  await cp(join(repositoryRoot, 'tools', 'content', 'templates', 'content.css'), join(sharedRoot, 'content.css'));
  const shell = await readFile(join(repositoryRoot, 'tools', 'content', 'templates', 'document.html'), 'utf8');
  const libraryCatalog = await loadLibraryCatalog(join(sourceRoot, 'library.json'));
  for (const [articleId] of expectedArticles) {
    const record = JSON.parse(await readFile(join(referenceRoot, 'articles', categoryId, articleId + '.json'), 'utf8'));
    const input = { record, articleAssetRoot: assetRoot, libraryCatalog, minAppVersionCode: 1 };
    assert.deepEqual(await buildReferencePackage(input), await buildReferencePackage(input));
    const html = shell.replaceAll('{{SHARED_ASSET_PREFIX}}', './shared').replace('{{TITLE}}', record.title).replace('{{CONTENT}}', renderReferenceArticle(record));
    await writeFile(join(contentRoot, articleId + '.html'), html, 'utf8');
  }
  const result = await renderReview(contentRoot, screenshotRoot, { widths: [336, 512, 1280] });
  assert.equal(result.files.length, 8);
  assert.equal(result.screenshots.length, 24);
});

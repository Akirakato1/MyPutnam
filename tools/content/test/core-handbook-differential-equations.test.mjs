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
const categoryId = 'differential-equations';
const expectedArticles = [
  ['separable-equations', 1, ['separable differential equation', 'lost solutions']],
  ['first-order-linear', 2, ['integrating factor', 'first-order linear ODE']],
  ['exact-and-bernoulli', 3, ['exact differential equation', 'Bernoulli equation']],
  ['second-order-homogeneous', 4, ['characteristic equation', 'repeated root']],
  ['forced-second-order', 5, ['undetermined coefficients', 'resonance']],
  ['variation-of-parameters', 6, ['variation of parameters', 'Wronskian']],
  ['linear-systems', 7, ['matrix exponential', 'linear system']],
];

test('differential equations ships the exact approved seven-article batch with domain and resonance conditions', async () => {
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

  assert.match(JSON.stringify(records.get('separable-equations').conditions), /lost solution/i);
  assert.match(JSON.stringify(records.get('separable-equations').conditions), /interval/i);
  assert.match(JSON.stringify(records.get('first-order-linear').conditions), /continuous/i);
  assert.match(JSON.stringify(records.get('exact-and-bernoulli').conditions), /nonzero/i);
  assert.match(JSON.stringify(records.get('second-order-homogeneous').conditions), /constant coefficients/i);
  assert.match(JSON.stringify(records.get('forced-second-order').conditions), /resonance/i);
  assert.match(JSON.stringify(records.get('variation-of-parameters').conditions), /Wronskian/i);
  assert.match(JSON.stringify(records.get('linear-systems').conditions), /matrix exponential/i);
  assert.deepEqual(await auditReferenceInventory({ sourceRoot, inventoryPath: join(referenceRoot, 'core-handbook-v1.json'), categoryIds: [categoryId] }), []);
});

test('differential-equation packages are deterministic and render at all review widths', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'myputnam-differential-equations-reference-'));
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
  assert.equal(result.files.length, 7);
  assert.equal(result.screenshots.length, 21);
});

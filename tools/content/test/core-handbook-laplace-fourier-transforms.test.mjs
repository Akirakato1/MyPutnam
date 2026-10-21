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
const categoryId = 'laplace-fourier-transforms';
const LAPLACE = 'laplace-one-sided-v1';
const SERIES = 'fourier-series-period-2L-v1';
const FOURIER = 'fourier-angular-v1';
const UNITARY = 'fourier-unitary-angular-v1';
const CYCLES = 'fourier-cycles-v1';
const expectedArticles = [
  ['laplace-definition-convergence', 1, ['Laplace transform', 'region of convergence'], [LAPLACE]],
  ['laplace-properties', 2, ['Laplace properties', 'initial value theorem'], [LAPLACE]],
  ['laplace-transform-table', 3, ['Laplace table', 'Laplace transform table'], [LAPLACE]],
  ['inverse-laplace', 4, ['inverse Laplace transform', 'partial fractions Laplace'], [LAPLACE]],
  ['convolution-and-ode', 5, ['Laplace convolution', 'Laplace ODE'], [LAPLACE]],
  ['fourier-series', 6, ['Fourier series', 'Fourier coefficients'], [SERIES]],
  ['fourier-transform-conventions', 7, ['Fourier transform', 'Fourier convention'], [FOURIER, UNITARY, CYCLES]],
  ['fourier-transform-properties', 8, ['Fourier properties', 'Fourier derivative rule'], [FOURIER]],
  ['fourier-transform-table', 9, ['Fourier table', 'Fourier transform table'], [FOURIER]],
  ['convolution-parseval-distributions', 10, ['Parseval theorem', 'Dirac delta Fourier'], [FOURIER]],
];

test('the transform convention registry locks signs, normalizations, variables, and domains', async () => {
  const registry = JSON.parse(await readFile(join(referenceRoot, 'laplace-fourier-conventions.json'), 'utf8'));
  assert.equal(registry.schemaVersion, 1);
  assert.deepEqual(registry.conventions.map(({ id }) => id), [LAPLACE, SERIES, FOURIER, UNITARY, CYCLES]);
  const byId = new Map(registry.conventions.map((entry) => [entry.id, entry]));
  assert.match(byId.get(LAPLACE).forward, /\\int_0\^\\infty e\^\{-st\}/);
  assert.match(byId.get(LAPLACE).domainNote, /region of convergence/i);
  assert.match(byId.get(SERIES).forward, /a_0.*2/);
  assert.match(byId.get(FOURIER).forward, /e\^\{-i\\omega x\}/);
  assert.match(byId.get(FOURIER).inverse, /1\{2\\pi\}.*e\^\{i\\omega x\}/);
  assert.match(byId.get(UNITARY).forward, /1\{\\sqrt\{2\\pi\}\}/);
  assert.match(byId.get(UNITARY).inverse, /1\{\\sqrt\{2\\pi\}\}/);
  assert.match(byId.get(CYCLES).forward, /e\^\{-2\\pi i\\xi x\}/);
  assert.doesNotMatch(byId.get(CYCLES).inverse, /\\frac/);
});

test('Laplace and Fourier ships the exact approved ten-article convention-safe batch', async () => {
  const inventory = JSON.parse(await readFile(join(referenceRoot, 'core-handbook-v1.json'), 'utf8'));
  const frozen = inventory.categories.find(({ id }) => id === categoryId);
  assert.deepEqual(frozen.articles.map(({ id, displayOrder }) => [id, displayOrder]), expectedArticles.map(([id, order]) => [id, order]));
  const libraryCatalog = await loadLibraryCatalog(join(sourceRoot, 'library.json'));
  const records = new Map();
  for (const [articleId, displayOrder, terms, conventionIds] of expectedArticles) {
    const record = JSON.parse(await readFile(join(referenceRoot, 'articles', categoryId, articleId + '.json'), 'utf8'));
    records.set(articleId, record);
    assert.equal(record.categoryId, categoryId);
    assert.equal(record.articleId, articleId);
    assert.equal(record.displayOrder, displayOrder);
    assert.deepEqual(validateReferenceArticle(record, { categories: libraryCatalog.referenceCategories }), []);
    const searchable = [...record.aliases, ...record.keywords].map((value) => value.toLocaleLowerCase('en-US'));
    for (const term of terms) assert.ok(searchable.includes(term.toLocaleLowerCase('en-US')), articleId + ' must be searchable by ' + term);
    for (const id of conventionIds) {
      assert.ok(record.keywords.includes(id), articleId + ' must index convention ' + id);
      assert.match(JSON.stringify([record.notation, record.definitions, record.results, record.conditions]), new RegExp(id), articleId + ' must cite convention ' + id);
    }
    assert.ok(record.examples.length >= 1);
    assert.equal(record.rights.redistributionStatus, 'cleared');
    const review = await readFile(join(referenceRoot, 'review', categoryId, articleId + '.md'), 'utf8');
    assert.match(review, /## Findings\r?\n- None\./);
    assert.match(review, /## Disposition\r?\napproved\s*$/);
  }
  assert.match(JSON.stringify(records.get('laplace-definition-convergence').conditions), /region of convergence/i);
  assert.match(JSON.stringify(records.get('laplace-properties').conditions), /boundary/i);
  assert.match(JSON.stringify(records.get('laplace-transform-table').conditions), /region of convergence/i);
  assert.match(JSON.stringify(records.get('inverse-laplace').conditions), /pole/i);
  assert.match(JSON.stringify(records.get('convolution-and-ode').conditions), /initial/i);
  assert.match(JSON.stringify(records.get('fourier-series').conditions), /jump/i);
  assert.match(JSON.stringify(records.get('fourier-transform-conventions').conditions), /normalization/i);
  assert.match(JSON.stringify(records.get('fourier-transform-properties').conditions), /integrable/i);
  assert.match(JSON.stringify(records.get('fourier-transform-table').conditions), /distribution/i);
  assert.match(JSON.stringify(records.get('convolution-parseval-distributions').conditions), /distribution/i);
  for (const articleId of ['laplace-transform-table', 'fourier-transform-table']) {
    const table = records.get(articleId).results.blocks.find(({ type }) => type === 'table');
    assert.ok(table, articleId + ' must provide a table');
    assert.equal(JSON.stringify(table.headers).includes('Convention ID'), true);
    for (const row of table.rows) assert.match(JSON.stringify(row), new RegExp(articleId.startsWith('laplace') ? LAPLACE : FOURIER));
  }
  assert.deepEqual(await auditReferenceInventory({ sourceRoot, inventoryPath: join(referenceRoot, 'core-handbook-v1.json'), categoryIds: [categoryId] }), []);
});

test('transform packages are deterministic and render at all review widths', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'myputnam-transform-reference-'));
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
  assert.equal(result.files.length, 10);
  assert.equal(result.screenshots.length, 30);
});

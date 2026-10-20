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
const categoryId = 'complex-numbers';
const expectedArticles = [
  ['complex-algebra', 1, ['complex conjugate', 'complex division']],
  ['modulus-argument', 2, ['complex modulus', 'complex argument', 'principal argument']],
  ['polar-form-de-moivre', 3, ['polar form', 'exponential form', "De Moivre's theorem"]],
  ['complex-roots', 4, ['nth roots', 'roots of complex numbers']],
  ['roots-of-unity', 5, ['roots of unity', 'root-of-unity filter']],
  ['complex-plane-geometry', 6, ['Argand plane', 'complex rotation', 'complex distance']],
];

test('complex numbers ships the exact approved six-article batch with branch-safe lookup metadata', async () => {
  const inventory = JSON.parse(await readFile(join(referenceRoot, 'core-handbook-v1.json'), 'utf8'));
  const frozen = inventory.categories.find(({ id }) => id === categoryId);
  assert.deepEqual(
    frozen.articles.map(({ id, displayOrder }) => [id, displayOrder]),
    expectedArticles.map(([id, displayOrder]) => [id, displayOrder]),
  );

  const libraryCatalog = await loadLibraryCatalog(join(sourceRoot, 'library.json'));
  for (const [articleId, displayOrder, requiredSearchTerms] of expectedArticles) {
    const record = JSON.parse(await readFile(join(referenceRoot, 'articles', categoryId, `${articleId}.json`), 'utf8'));
    assert.equal(record.categoryId, categoryId);
    assert.equal(record.articleId, articleId);
    assert.equal(record.displayOrder, displayOrder);
    assert.deepEqual(validateReferenceArticle(record, { categories: libraryCatalog.referenceCategories }), []);
    const searchable = [...record.aliases, ...record.keywords].map((value) => value.toLocaleLowerCase('en-US'));
    for (const term of requiredSearchTerms) {
      assert.ok(searchable.includes(term.toLocaleLowerCase('en-US')), `${articleId} must be searchable by ${term}`);
    }
    assert.ok(record.examples.length >= 1, `${articleId} requires a worked example`);
    assert.equal(record.rights.redistributionStatus, 'cleared');
    const review = await readFile(join(referenceRoot, 'review', categoryId, `${articleId}.md`), 'utf8');
    assert.match(review, /## Findings\r?\n- None\./);
    assert.match(review, /## Disposition\r?\napproved\s*$/);
  }

  const roots = JSON.parse(await readFile(join(referenceRoot, 'articles', categoryId, 'complex-roots.json'), 'utf8'));
  assert.ok(roots.relatedReferences.includes('math-reference/complex-numbers/modulus-argument'));
  assert.deepEqual(await auditReferenceInventory({
    sourceRoot,
    inventoryPath: join(referenceRoot, 'core-handbook-v1.json'),
    categoryIds: [categoryId],
  }), []);
});

test('complex-number packages are deterministic and render at all review widths', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'myputnam-complex-reference-'));
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
    const record = JSON.parse(await readFile(join(referenceRoot, 'articles', categoryId, `${articleId}.json`), 'utf8'));
    const input = { record, articleAssetRoot: assetRoot, libraryCatalog, minAppVersionCode: 1 };
    assert.deepEqual(await buildReferencePackage(input), await buildReferencePackage(input), `${articleId} package must be deterministic`);
    const html = shell
      .replaceAll('{{SHARED_ASSET_PREFIX}}', './shared')
      .replace('{{TITLE}}', record.title)
      .replace('{{CONTENT}}', renderReferenceArticle(record));
    await writeFile(join(contentRoot, `${articleId}.html`), html, 'utf8');
  }

  const result = await renderReview(contentRoot, screenshotRoot, { widths: [336, 512, 1280] });
  assert.equal(result.files.length, 6);
  assert.equal(result.screenshots.length, 18);
});

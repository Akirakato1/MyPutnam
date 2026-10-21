import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cp, mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative, resolve } from 'node:path';
import test from 'node:test';

import { auditPublication } from '../src/audit-publication.mjs';
import { buildPublication } from '../src/build-publication.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';
import { renderReferenceArticle } from '../src/render-reference.mjs';
import { renderReview } from '../src/render-review.mjs';

const repositoryRoot = resolve(import.meta.dirname, '..', '..', '..');
const sourceRoot = join(repositoryRoot, 'content-src');
const referenceRoot = join(sourceRoot, 'reference');
const publicationPath = join(sourceRoot, 'publications', 'content-v1.json');

test('the complete local publication is deterministic and the handbook is rights-cleared', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'myputnam-core-handbook-publication-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const firstRoot = join(root, 'first');
  const secondRoot = join(root, 'second');
  const [libraryCatalog, publicationManifest] = await Promise.all([
    loadLibraryCatalog(join(sourceRoot, 'library.json')),
    readJson(publicationPath),
  ]);
  const options = { sourceRoot, libraryCatalog, publicationManifest };
  const first = await buildPublication({ ...options, outputRoot: firstRoot });
  const second = await buildPublication({ ...options, outputRoot: secondRoot });
  assert.deepEqual(first, {
    catalogPath: join(firstRoot, 'content', 'v1', 'catalog.json'),
    problemCount: 72,
    referenceCount: 97,
    packageCount: 169,
  });
  assert.deepEqual({ ...second, catalogPath: first.catalogPath }, first);
  assert.deepEqual(await hashTree(firstRoot), await hashTree(secondRoot));
  assert.deepEqual(await auditPublication(firstRoot), []);

  const publicRightsErrors = await auditPublication(firstRoot, { requireClearedRights: true });
  assert.equal(publicRightsErrors.length, 72);
  assert.equal(publicRightsErrors.every((error) =>
    /^catalog\.problems\[\d+\]\.rights\.redistributionStatus: public publication requires cleared rights$/.test(error)), true);

  const handbookOnlyRoot = join(root, 'handbook-only');
  const handbookOnlyManifest = { ...publicationManifest, includedSets: [] };
  const handbookOnly = await buildPublication({
    sourceRoot,
    libraryCatalog,
    publicationManifest: handbookOnlyManifest,
    outputRoot: handbookOnlyRoot,
  });
  assert.deepEqual(handbookOnly, {
    catalogPath: join(handbookOnlyRoot, 'content', 'v1', 'catalog.json'),
    problemCount: 0,
    referenceCount: 97,
    packageCount: 97,
  });
  assert.deepEqual(await auditPublication(handbookOnlyRoot, { requireClearedRights: true }), []);

  const catalog = await readJson(first.catalogPath);
  assert.equal(catalog.catalogRevision, publicationManifest.catalogRevision);
  assert.equal(catalog.referenceCategories.length, 12);
  assert.equal(catalog.references.length, 97);
  assert.equal(new Set(catalog.references.map(({ key }) => key)).size, 97);
  assert.equal(catalog.references.every(({ rights, package: item }) =>
    rights.redistributionStatus === 'cleared'
    && item.path.startsWith('packages/reference/')
    && item.path.endsWith('/' + item.sha256 + '.zip')), true);
  const tree = await hashTree(firstRoot);
  assert.equal(tree.filter(({ path }) => path.endsWith('.zip')).length, 169);
  assert.equal(tree.some(({ path }) => /review|source-lock|provenance/i.test(path)), false);
});

test('all 97 handbook articles render at phone, split-tablet, and wide widths', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'myputnam-core-handbook-render-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const contentRoot = join(root, 'content');
  const screenshotRoot = join(root, 'screenshots');
  const sharedRoot = join(contentRoot, 'shared');
  await mkdir(sharedRoot, { recursive: true });
  await cp(join(repositoryRoot, 'tools', 'content', 'node_modules', 'katex', 'dist'), join(sharedRoot, 'katex'), { recursive: true });
  await cp(join(repositoryRoot, 'tools', 'content', 'templates', 'content.css'), join(sharedRoot, 'content.css'));
  const shell = await readFile(join(repositoryRoot, 'tools', 'content', 'templates', 'document.html'), 'utf8');
  const inventory = await readJson(join(referenceRoot, 'core-handbook-v1.json'));
  for (const category of inventory.categories) {
    for (const article of category.articles) {
      const record = await readJson(join(referenceRoot, 'articles', category.id, article.id + '.json'));
      const html = shell.replaceAll('{{SHARED_ASSET_PREFIX}}', './shared')
        .replace('{{TITLE}}', record.title)
        .replace('{{CONTENT}}', renderReferenceArticle(record));
      await writeFile(join(contentRoot, category.id + '--' + article.id + '.html'), html, 'utf8');
    }
  }
  const result = await renderReview(contentRoot, screenshotRoot, { widths: [336, 512, 1280] });
  assert.equal(result.files.length, 97);
  assert.equal(result.screenshots.length, 291);
});

async function readJson(path) {
  return JSON.parse(await readFile(path, 'utf8'));
}

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
    if (entry.isDirectory()) await visit(root, path, files);
    else {
      const bytes = await readFile(path);
      files.push({
        path: relative(root, path).replaceAll('\\', '/'),
        sha256: createHash('sha256').update(bytes).digest('hex'),
      });
    }
  }
}

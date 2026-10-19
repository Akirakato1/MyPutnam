import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { access, mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import test from 'node:test';

import { buildPublication } from '../src/build-publication.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';

const sourceRoot = new URL('../../../content-src/', import.meta.url);
const libraryUrl = new URL('../../../content-src/library.json', import.meta.url);
const publicationUrl = new URL('../../../content-src/publications/content-v1.json', import.meta.url);

test('publication builder emits one deterministic catalog and 72 referenced immutable packages', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'myputnam-publication-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const firstRoot = join(root, 'first');
  const secondRoot = join(root, 'second');
  const [libraryCatalog, publicationManifest] = await Promise.all([
    loadLibraryCatalog(libraryUrl), readJson(publicationUrl),
  ]);
  const options = { sourceRoot, libraryCatalog, publicationManifest };
  const first = await buildPublication({ ...options, outputRoot: firstRoot });
  const second = await buildPublication({ ...options, outputRoot: secondRoot });
  assert.deepEqual(first, {
    catalogPath: join(firstRoot, 'content', 'v1', 'catalog.json'),
    problemCount: 72, referenceCount: 0, packageCount: 72,
  });
  assert.deepEqual({ ...second, catalogPath: first.catalogPath }, first);
  assert.deepEqual(await hashTree(firstRoot), await hashTree(secondRoot));

  const catalog = await readJson(first.catalogPath);
  assert.equal(catalog.problems.length, 72);
  assert.equal(catalog.references.length, 0);
  assert.equal(catalog.problems.every(({ package: item }) =>
    item.path.startsWith('packages/problem/putnam/') && item.path.endsWith(`/${item.sha256}.zip`)), true);
  const tree = await hashTree(firstRoot);
  assert.equal(tree.filter(({ path }) => path.endsWith('.zip')).length, 72);
  assert.deepEqual(tree.filter(({ path }) => path.endsWith('.json')).map(({ path }) => path), ['content/v1/catalog.json']);
  for (const problem of catalog.problems) await access(join(firstRoot, 'content', 'v1', ...problem.package.path.split('/')));
});

test('publication builder refuses a nonempty output root without changing it', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'myputnam-publication-nonempty-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(root, { recursive: true });
  await writeFile(join(root, 'sentinel.txt'), 'preserve');
  const [libraryCatalog, publicationManifest] = await Promise.all([
    loadLibraryCatalog(libraryUrl), readJson(publicationUrl),
  ]);
  await assert.rejects(buildPublication({ sourceRoot, libraryCatalog, publicationManifest, outputRoot: root }), /empty|exists/i);
  assert.equal(await readFile(join(root, 'sentinel.txt'), 'utf8'), 'preserve');
});

test('publication builder emits selected reference packages and catalog metadata', async (t) => {
  const parent = await mkdtemp(join(tmpdir(), 'myputnam-reference-publication-'));
  t.after(() => rm(parent, { recursive: true, force: true }));
  const source = join(parent, 'source');
  const articleRoot = join(source, 'reference', 'articles', 'number-theory');
  await mkdir(articleRoot, { recursive: true });
  const article = await readJson(new URL('./fixtures/contracts/valid-reference.json', import.meta.url));
  article.relatedReferences = [];
  article.relatedProblems = [];
  await writeFile(join(articleRoot, 'euler-totient.json'), JSON.stringify(article));
  const [libraryCatalog, publicationManifest] = await Promise.all([
    loadLibraryCatalog(libraryUrl), readJson(publicationUrl),
  ]);
  publicationManifest.includedSets = [];
  publicationManifest.includedReferenceCategories = ['number-theory'];

  const result = await buildPublication({
    sourceRoot: source, libraryCatalog, publicationManifest, outputRoot: join(parent, 'output'),
  });
  assert.deepEqual({ ...result, catalogPath: 'catalog' }, {
    catalogPath: 'catalog', problemCount: 0, referenceCount: 1, packageCount: 1,
  });
  const catalog = await readJson(result.catalogPath);
  assert.equal(catalog.references[0].key, 'math-reference/number-theory/euler-totient');
  assert.match(catalog.references[0].package.path,
    /^packages\/reference\/number-theory\/euler-totient\/[0-9a-f]{64}\.zip$/);
  await access(join(parent, 'output', 'content', 'v1', ...catalog.references[0].package.path.split('/')));
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

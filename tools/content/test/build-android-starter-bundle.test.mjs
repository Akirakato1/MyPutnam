import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { access, cp, mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import test, { after, before } from 'node:test';

import { buildPublication } from '../src/build-publication.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';

const repositoryRoot = resolve(import.meta.dirname, '..', '..', '..');
const sourceRoot = join(repositoryRoot, 'content-src');
const publicationManifestPath = join(sourceRoot, 'publications', 'content-v1.json');
let fixtureRoot;
let publicationRoot;
let catalog;
let catalogBytes;

before(async () => {
  fixtureRoot = await mkdtemp(join(tmpdir(), 'myputnam-starter-bundle-test-'));
  publicationRoot = join(fixtureRoot, 'publication');
  const [libraryCatalog, publicationManifest] = await Promise.all([
    loadLibraryCatalog(join(sourceRoot, 'library.json')),
    readJson(publicationManifestPath),
  ]);
  await buildPublication({ sourceRoot, libraryCatalog, publicationManifest, outputRoot: publicationRoot });
  catalogBytes = await readFile(join(publicationRoot, 'content', 'v1', 'catalog.json'));
  catalog = JSON.parse(catalogBytes);
});

after(async () => {
  if (fixtureRoot) await rm(fixtureRoot, { recursive: true, force: true });
});

test('development release manifest freezes the current audited catalog and explicit rights exception', async () => {
  const manifest = await readJson(join(sourceRoot, 'releases', 'myputnam-v0.5.0-development.json'));
  assert.deepEqual(manifest, releaseManifestFor(catalog, catalogBytes));
});

test('starter bundle copies the exact complete catalog, packages, and renderer deterministically', async (t) => {
  const buildAndroidStarterBundle = await loadBuilder(t);
  const releaseManifest = releaseManifestFor(catalog, catalogBytes);
  const firstRoot = join(fixtureRoot, 'starter-first');
  const secondRoot = join(fixtureRoot, 'starter-second');
  const first = await buildAndroidStarterBundle({ publicationRoot, releaseManifest, outputRoot: firstRoot });
  const second = await buildAndroidStarterBundle({ publicationRoot, releaseManifest, outputRoot: secondRoot });

  assert.deepEqual(first, {
    catalogSha256: sha256(catalogBytes),
    packageCount: 169,
    problemCount: 72,
    referenceCount: 97,
  });
  assert.deepEqual(second, first);
  assert.deepEqual(await hashTree(firstRoot), await hashTree(secondRoot));
  assert.deepEqual(await readFile(join(firstRoot, 'catalog.json')), catalogBytes);
  assert.deepEqual(await readJson(join(firstRoot, 'release.json')), releaseManifest);

  const descriptors = [...catalog.problems, ...catalog.references].map((entry) => entry.package);
  assert.equal(new Set(descriptors.map(({ sha256: digest }) => digest)).size, 169);
  for (const descriptor of descriptors) {
    const source = join(publicationRoot, 'content', 'v1', ...descriptor.path.split('/'));
    const target = join(firstRoot, 'packages', descriptor.sha256 + '.zip');
    assert.deepEqual(await readFile(target), await readFile(source), descriptor.path);
  }

  assert.deepEqual(
    await readFile(join(firstRoot, 'renderer', 'document.html')),
    await readFile(join(repositoryRoot, 'tools', 'content', 'templates', 'document.html')),
  );
  assert.deepEqual(
    await readFile(join(firstRoot, 'renderer', 'content.css')),
    await readFile(join(repositoryRoot, 'tools', 'content', 'templates', 'content.css')),
  );
  assert.deepEqual(
    await readFile(join(firstRoot, 'renderer', 'katex', 'katex.min.css')),
    await readFile(join(repositoryRoot, 'tools', 'content', 'node_modules', 'katex', 'dist', 'katex.min.css')),
  );
  const fontFiles = await readdir(join(firstRoot, 'renderer', 'katex', 'fonts'));
  assert.ok(fontFiles.length >= 20);

  const tree = await hashTree(firstRoot);
  assert.equal(tree.filter(({ path }) => path.startsWith('packages/') && path.endsWith('.zip')).length, 169);
  assert.equal(tree.some(({ path }) => /(?:^|\/)(?:source|review|original|provenance)(?:\/|\.|$)/i.test(path)), false);
  assert.deepEqual(tree.filter(({ path }) => path.endsWith('.json')).map(({ path }) => path).sort(), [
    'catalog.json', 'release.json',
  ]);
});

test('starter bundle rejects stale release metadata and unsafe publication intent', async (t) => {
  const buildAndroidStarterBundle = await loadBuilder(t);
  const valid = releaseManifestFor(catalog, catalogBytes);
  const cases = [
    ['problem count', { ...valid, expectedProblemCount: 71 }, /expectedProblemCount/i],
    ['reference count', { ...valid, expectedReferenceCount: 96 }, /expectedReferenceCount/i],
    ['package count', { ...valid, expectedPackageCount: 168 }, /expectedPackageCount/i],
    ['catalog hash', { ...valid, catalogSha256: '0'.repeat(64) }, /catalogSha256/i],
    ['catalog revision', { ...valid, catalogRevision: 'wrong-revision' }, /catalogRevision/i],
    ['catalog id', { ...valid, catalogId: 'wrong-catalog' }, /catalogId/i],
    ['minimum app version', { ...valid, appVersionCode: 0 }, /appVersionCode|minAppVersionCode/i],
    ['duplicate set', { ...valid, includedSets: [...valid.includedSets, valid.includedSets[0]] }, /duplicate.*includedSets/i],
    ['duplicate category', { ...valid, includedReferenceCategories: [...valid.includedReferenceCategories, valid.includedReferenceCategories[0]] }, /duplicate.*includedReferenceCategories/i],
    ['public rights gate', { ...valid, distributionScope: 'public-release', allowedUnresolvedContentKeys: [] }, /public-release.*cleared rights/i],
  ];
  for (const [name, manifest, expected] of cases) {
    await assert.rejects(
      buildAndroidStarterBundle({ publicationRoot, releaseManifest: manifest, outputRoot: join(fixtureRoot, 'invalid-' + slug(name)) }),
      expected,
      name,
    );
  }
});

test('local-development scope requires an exact reviewed unresolved-rights allowlist', async (t) => {
  const buildAndroidStarterBundle = await loadBuilder(t);
  const valid = releaseManifestFor(catalog, catalogBytes);
  await assert.rejects(
    buildAndroidStarterBundle({
      publicationRoot,
      releaseManifest: { ...valid, allowedUnresolvedContentKeys: valid.allowedUnresolvedContentKeys.slice(1) },
      outputRoot: join(fixtureRoot, 'missing-rights-key'),
    }),
    /allowedUnresolvedContentKeys.*does not match/i,
  );
  await assert.rejects(
    buildAndroidStarterBundle({
      publicationRoot,
      releaseManifest: { ...valid, allowedUnresolvedContentKeys: [...valid.allowedUnresolvedContentKeys, 'putnam/2099/A1'] },
      outputRoot: join(fixtureRoot, 'extra-rights-key'),
    }),
    /allowedUnresolvedContentKeys.*does not match/i,
  );
});

test('starter bundle rejects missing, mutated, orphaned, and mutable-path packages', async (t) => {
  const buildAndroidStarterBundle = await loadBuilder(t);
  const mutations = [
    ['missing', async (root, mutableCatalog) => {
      const descriptor = mutableCatalog.problems[0].package;
      await rm(join(root, 'content', 'v1', ...descriptor.path.split('/')));
    }, /missing package/i],
    ['mutated', async (root, mutableCatalog) => {
      const descriptor = mutableCatalog.problems[0].package;
      await writeFile(join(root, 'content', 'v1', ...descriptor.path.split('/')), Buffer.from('corrupt'));
    }, /digest|size|archive|zip/i],
    ['orphaned', async (root) => {
      await writeFile(join(root, 'content', 'v1', 'packages', 'orphan.zip'), Buffer.from('orphan'));
    }, /orphan package/i],
    ['mutable-path', async (root, mutableCatalog) => {
      mutableCatalog.problems[0].package.path = 'packages/problem/putnam/2020/A1/current.zip';
      await writeCatalog(root, mutableCatalog);
    }, /immutable filename\/path/i],
    ['duplicate-key', async (root, mutableCatalog) => {
      mutableCatalog.problems[1].key = mutableCatalog.problems[0].key;
      await writeCatalog(root, mutableCatalog);
    }, /duplicate problem key/i],
  ];
  for (const [name, mutate, expected] of mutations) {
    const root = join(fixtureRoot, 'publication-' + name);
    await cp(publicationRoot, root, { recursive: true });
    const mutableCatalog = structuredClone(catalog);
    await mutate(root, mutableCatalog);
    const bytes = await readFile(join(root, 'content', 'v1', 'catalog.json'));
    const manifest = releaseManifestFor(JSON.parse(bytes), bytes);
    await assert.rejects(
      buildAndroidStarterBundle({ publicationRoot: root, releaseManifest: manifest, outputRoot: join(fixtureRoot, 'starter-' + name) }),
      expected,
      name,
    );
  }
});

test('starter bundle refuses a nonempty output root without changing it', async (t) => {
  const buildAndroidStarterBundle = await loadBuilder(t);
  const outputRoot = join(fixtureRoot, 'nonempty-output');
  await mkdir(outputRoot, { recursive: true });
  await writeFile(join(outputRoot, 'sentinel.txt'), 'preserve');
  await assert.rejects(
    buildAndroidStarterBundle({ publicationRoot, releaseManifest: releaseManifestFor(catalog, catalogBytes), outputRoot }),
    /output root must be empty/i,
  );
  assert.equal(await readFile(join(outputRoot, 'sentinel.txt'), 'utf8'), 'preserve');
});

async function loadBuilder(t) {
  try {
    const module = await import('../src/build-android-starter-bundle.mjs');
    return module.buildAndroidStarterBundle;
  } catch (error) {
    t.assert.fail('buildAndroidStarterBundle is not implemented: ' + error.message);
  }
}

function releaseManifestFor(value, bytes) {
  const unresolved = [...value.problems, ...value.references]
    .filter(({ rights }) => rights.redistributionStatus !== 'cleared')
    .map(({ key }) => key)
    .sort();
  return {
    schemaVersion: 1,
    releaseId: 'myputnam-v0.5.0-development',
    distributionScope: 'local-development',
    appVersionName: '0.5.0-dev',
    appVersionCode: 5,
    catalogId: value.catalogId,
    catalogRevision: value.catalogRevision,
    catalogSha256: sha256(bytes),
    generatedAt: value.generatedAt,
    feedRoot: 'https://akirakato1.github.io/MyPutnam/content/v1/',
    minAppVersionCode: value.minAppVersionCode,
    supportedSchemaVersions: {
      catalog: [1], package: [1], problem: [3], reference: [1], richDocument: [1, 2],
    },
    includedSets: [
      { collectionId: 'putnam', setId: '2020' }, { collectionId: 'putnam', setId: '2021' },
      { collectionId: 'putnam', setId: '2022' }, { collectionId: 'putnam', setId: '2023' },
      { collectionId: 'putnam', setId: '2024' }, { collectionId: 'putnam', setId: '2025' },
    ],
    includedReferenceCategories: value.referenceCategories.map(({ id }) => id),
    expectedProblemCount: 72,
    expectedReferenceCount: 97,
    expectedPackageCount: 169,
    allowedUnresolvedContentKeys: unresolved,
  };
}

async function writeCatalog(root, value) {
  await writeFile(join(root, 'content', 'v1', 'catalog.json'), JSON.stringify(value, null, 2) + '\n');
}

async function readJson(path) {
  return JSON.parse(await readFile(path, 'utf8'));
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function slug(value) {
  return value.replaceAll(/[^a-z0-9]+/gi, '-').toLowerCase();
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
      files.push({ path: relative(root, path).replaceAll('\\', '/'), sha256: sha256(bytes) });
    }
  }
}

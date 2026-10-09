import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  access,
  cp,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import test from 'node:test';

import {
  assertUniqueProblemKeys,
  buildArchive,
} from '../src/build-archive.mjs';
import { createYearFixture } from './helpers/year-fixture.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';

const libraryCatalogUrl = new URL('../../../content-src/library.json', import.meta.url);

test('a complete archive builds sorted manifests and one physical shared-asset set', async (t) => {
  const fixture = await createArchiveFixture(t);
  const outputRoot = join(fixture.root, 'output');
  const reviewRoot = join(fixture.root, 'review');

  const result = await buildArchive({
    archiveCatalog: fixture.catalog,
    libraryCatalog: fixture.libraryCatalog,
    sourceRoot: fixture.sourceRoot,
    outputRoot,
    reviewRoot,
  });

  assert.deepEqual(result.manifests.map(({ setId }) => setId), ['2023', '2024']);
  assert.equal(result.archiveManifest.problemCount, 24);
  assert.deepEqual(result.archiveManifest.years, [2023, 2024]);
  assert.equal(
    new Set(result.archiveManifest.sharedAssets.map(({ path }) => path)).size,
    result.archiveManifest.sharedAssets.length,
  );

  await access(join(outputRoot, 'content', 'shared', 'content.css'));
  await assert.rejects(access(join(outputRoot, 'content', 'putnam', '2023', 'shared')), { code: 'ENOENT' });
  await assert.rejects(access(join(outputRoot, 'content', 'putnam', '2024', 'shared')), { code: 'ENOENT' });
  const html = await readFile(
    join(outputRoot, 'content', 'putnam', '2023', 'problems', 'A1', 'statement.html'),
    'utf8',
  );
  assert.match(html, /href="\.\.\/\.\.\/\.\.\/\.\.\/shared\//);

  const onDiskArchive = JSON.parse(
    await readFile(join(outputRoot, 'content', 'archive-manifest.json'), 'utf8'),
  );
  assert.deepEqual(onDiskArchive, result.archiveManifest);
});

test('identical inputs produce deterministic logical archive output', async (t) => {
  const fixture = await createArchiveFixture(t);
  const firstOutput = join(fixture.root, 'output-first');
  const secondOutput = join(fixture.root, 'output-second');

  const first = await buildArchive({
    archiveCatalog: fixture.catalog, libraryCatalog: fixture.libraryCatalog,
    sourceRoot: fixture.sourceRoot, outputRoot: firstOutput, reviewRoot: join(fixture.root, 'review-first'),
  });
  const second = await buildArchive({
    archiveCatalog: structuredClone(fixture.catalog), libraryCatalog: fixture.libraryCatalog,
    sourceRoot: fixture.sourceRoot, outputRoot: secondOutput, reviewRoot: join(fixture.root, 'review-second'),
  });

  assert.deepEqual(second, first);
  assert.deepEqual(await logicalTree(secondOutput), await logicalTree(firstOutput));
});

test('incomplete catalogs are rejected before any output is written', async (t) => {
  const fixture = await createArchiveFixture(t);
  fixture.catalog.completedYears = [2024];
  const outputRoot = join(fixture.root, 'output');
  const reviewRoot = join(fixture.root, 'review');

  await assert.rejects(
    buildArchive({ archiveCatalog: fixture.catalog, libraryCatalog: fixture.libraryCatalog, sourceRoot: fixture.sourceRoot, outputRoot, reviewRoot }),
    /incomplete/i,
  );
  await assert.rejects(access(outputRoot), { code: 'ENOENT' });
  await assert.rejects(access(reviewRoot), { code: 'ENOENT' });
});

test('missing and extra numeric year directories are rejected', async (t) => {
  const missing = await createArchiveFixture(t);
  await rm(join(missing.sourceRoot, '2023'), { recursive: true, force: true });
  await assert.rejects(
    buildArchive({ archiveCatalog: missing.catalog, libraryCatalog: missing.libraryCatalog, sourceRoot: missing.sourceRoot, outputRoot: join(missing.root, 'output'), reviewRoot: join(missing.root, 'review') }),
    /missing.*2023/i,
  );

  const extra = await createArchiveFixture(t);
  await mkdir(join(extra.sourceRoot, '2022'));
  await assert.rejects(
    buildArchive({ archiveCatalog: extra.catalog, libraryCatalog: extra.libraryCatalog, sourceRoot: extra.sourceRoot, outputRoot: join(extra.root, 'output'), reviewRoot: join(extra.root, 'review') }),
    /extra.*2022/i,
  );
});

test('repeated year and problem-code keys are rejected explicitly', () => {
  assert.throws(
    () => assertUniqueProblemKeys([
      { collectionId: 'putnam', setId: '2024', problems: [{ key: 'putnam/2024/A1' }] },
      { collectionId: 'putnam', setId: '2024', problems: [{ key: 'putnam/2024/A1' }] },
    ]),
    /duplicate problem key.*putnam\/2024\/A1/i,
  );
});

test('archive builds do not replace files outside supplied output and review roots', async (t) => {
  const fixture = await createArchiveFixture(t);
  const sentinel = join(fixture.root, 'sentinel.txt');
  await writeFile(sentinel, 'preserve me', 'utf8');

  await buildArchive({
    archiveCatalog: fixture.catalog, libraryCatalog: fixture.libraryCatalog,
    sourceRoot: fixture.sourceRoot, outputRoot: join(fixture.root, 'output'), reviewRoot: join(fixture.root, 'review'),
  });

  assert.equal(await readFile(sentinel, 'utf8'), 'preserve me');
  assert.deepEqual(
    (await readdir(fixture.root)).sort(),
    ['output', 'review', 'sentinel.txt', 'source'],
  );
});

async function createArchiveFixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'putnam-archive-build-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const sourceRoot = join(root, 'source');
  await mkdir(sourceRoot);

  for (const year of [2023, 2024]) {
    const fixture = await createYearFixture(t, year);
    await cp(fixture.sourceRoot, join(sourceRoot, String(year)), { recursive: true });
  }

  return {
    root,
    sourceRoot,
    catalog: {
      schemaVersion: 1,
      contentSchemaVersion: 3,
      contentVersion: 'archive-2023-2024.1',
      targetYears: [2023, 2024],
      completedYears: [2023, 2024],
    },
    libraryCatalog: await loadLibraryCatalog(libraryCatalogUrl),
  };
}

async function logicalTree(root) {
  const output = [];
  await visit(root, root, output);
  return output;
}

async function visit(root, directory, output) {
  const entries = await readdir(directory, { withFileTypes: true });
  entries.sort((left, right) => left.name.localeCompare(right.name));
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      await visit(root, path, output);
    } else if (entry.isFile()) {
      const bytes = await readFile(path);
      output.push({
        path: relative(root, path).replaceAll('\\', '/'),
        bytes: bytes.byteLength,
        sha256: createHash('sha256').update(bytes).digest('hex'),
      });
    }
  }
}

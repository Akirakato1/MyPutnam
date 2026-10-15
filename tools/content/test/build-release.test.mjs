import assert from 'node:assert/strict';
import { access, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { loadArchiveCatalog } from '../src/archive-catalog.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';
import { loadReleaseManifest } from '../src/release-manifest.mjs';

let buildContract = {};
try {
  buildContract = await import('../src/build-release.mjs');
} catch {
  // RED: the release builder is introduced by this task.
}

const sourceRoot = new URL('../../../content-src/', import.meta.url);
const archiveUrl = new URL('../../../content-src/archive.json', import.meta.url);
const libraryUrl = new URL('../../../content-src/library.json', import.meta.url);
const releaseUrl = new URL('../../../content-src/releases/putnam-2020-2025-v0.4.0.json', import.meta.url);

test('release builder stages exactly the six selected sets with deterministic counts', async (t) => {
  assert.equal(typeof buildContract.buildRelease, 'function');
  const root = await mkdtemp(join(tmpdir(), 'putnam-release-build-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const stagingRoot = join(root, 'app-assets');
  const receiptPath = join(root, 'bundle-receipt.json');
  const [archiveCatalog, libraryCatalog] = await Promise.all([
    loadArchiveCatalog(archiveUrl),
    loadLibraryCatalog(libraryUrl),
  ]);
  const releaseManifest = await loadReleaseManifest(releaseUrl, archiveCatalog, libraryCatalog);

  const result = await buildContract.buildRelease({
    archiveCatalog,
    libraryCatalog,
    releaseManifest,
    sourceRoot,
    stagingRoot,
    reviewRoot: join(root, 'review'),
    receiptPath,
  });

  assert.deepEqual(result.summary, {
    collectionCount: 1,
    setCount: 6,
    topicCount: 13,
    problemCount: 72,
    hintCount: 288,
    prerequisiteCount: 219,
  });
  assert.deepEqual(
    (await readdir(join(stagingRoot, 'content', 'putnam'))).sort(),
    ['2020', '2021', '2022', '2023', '2024', '2025'],
  );
  assert.equal(result.setManifestPaths.length, 6);
  assert.deepEqual(JSON.parse(await readFile(result.releaseManifestPath, 'utf8')), releaseManifest);
  await access(result.databasePath);
  const receipt = JSON.parse(await readFile(receiptPath, 'utf8'));
  assert.equal(receipt.releaseId, releaseManifest.releaseId);
  assert.deepEqual(receipt.counts, result.summary);
  assert.equal('generatedAt' in receipt, false);
});

test('release builder refuses a reused staging directory before changing it', async (t) => {
  assert.equal(typeof buildContract.buildRelease, 'function');
  const root = await mkdtemp(join(tmpdir(), 'putnam-release-nonempty-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const stagingRoot = join(root, 'app-assets');
  await mkdir(stagingRoot, { recursive: true });
  await writeFile(join(stagingRoot, 'sentinel.txt'), 'preserve', 'utf8');
  const [archiveCatalog, libraryCatalog] = await Promise.all([
    loadArchiveCatalog(archiveUrl),
    loadLibraryCatalog(libraryUrl),
  ]);
  const releaseManifest = await loadReleaseManifest(releaseUrl, archiveCatalog, libraryCatalog);

  await assert.rejects(
    buildContract.buildRelease({
      archiveCatalog, libraryCatalog, releaseManifest, sourceRoot,
      stagingRoot, reviewRoot: join(root, 'review'), receiptPath: join(root, 'receipt.json'),
    }),
    /staging.*empty|already exists/i,
  );
  assert.equal(await readFile(join(stagingRoot, 'sentinel.txt'), 'utf8'), 'preserve');
});

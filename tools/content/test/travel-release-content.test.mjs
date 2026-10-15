import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { loadArchiveCatalog } from '../src/archive-catalog.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';
import { loadReleaseManifest } from '../src/release-manifest.mjs';

let contract = {};
try {
  contract = await import('../src/build-release.mjs');
} catch {
  // RED: the release builder is introduced by this task.
}

test('the real travel corpus produces coherent generic paths and release metadata', async (t) => {
  assert.equal(typeof contract.buildRelease, 'function');
  const root = await mkdtemp(join(tmpdir(), 'putnam-travel-content-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const archiveUrl = new URL('../../../content-src/archive.json', import.meta.url);
  const libraryUrl = new URL('../../../content-src/library.json', import.meta.url);
  const releaseUrl = new URL('../../../content-src/releases/putnam-2020-2025-v0.4.0.json', import.meta.url);
  const archiveCatalog = await loadArchiveCatalog(archiveUrl);
  const libraryCatalog = await loadLibraryCatalog(libraryUrl);
  const releaseManifest = await loadReleaseManifest(releaseUrl, archiveCatalog, libraryCatalog);
  const result = await contract.buildRelease({
    archiveCatalog,
    libraryCatalog,
    releaseManifest,
    sourceRoot: new URL('../../../content-src/', import.meta.url),
    stagingRoot: join(root, 'app-assets'),
    reviewRoot: join(root, 'review'),
    receiptPath: join(root, 'bundle-receipt.json'),
  });

  for (const manifestPath of result.setManifestPaths) {
    const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
    assert.equal(manifest.problems.length, 12);
    assert.ok(manifest.assets.every(({ path }) => path.startsWith('content/')));
    assert.ok(manifest.problems.every(({ key, statement }) =>
      key === `${manifest.collectionId}/${manifest.setId}/${key.split('/').at(-1)}`
        && statement.assetPath.startsWith(`content/${manifest.collectionId}/${manifest.setId}/problems/`)));
  }
});

import assert from 'node:assert/strict';
import { access, mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { loadLibraryCatalog } from '../src/library-catalog.mjs';
import { buildSet } from '../src/build-set.mjs';

const repositoryRoot = fileURLToPath(new URL('../../..', import.meta.url));
const sourceRoot = join(repositoryRoot, 'content-src', '2025');
const catalogPath = join(repositoryRoot, 'content-src', 'library.json');

test('generic set build emits canonical keys and final asset-root-relative paths', async () => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), 'math-library-set-'));
  try {
    const catalog = await loadLibraryCatalog(catalogPath);
    const manifest = await buildSet(sourceRoot, temporaryRoot, {
      catalog,
      collectionId: 'putnam',
      setId: '2025',
      contentVersion: 'putnam-2025.test',
      problems: ['A1'],
    });

    assert.equal(manifest.schemaVersion, 3);
    assert.equal(manifest.collectionId, 'putnam');
    assert.equal(manifest.setId, '2025');
    assert.equal(manifest.problems[0].key, 'putnam/2025/A1');
    assert.equal(
      manifest.problems[0].statement.assetPath,
      'content/putnam/2025/problems/A1/statement.html',
    );
    assert.ok(manifest.catalogSha256.match(/^[a-f0-9]{64}$/));
    assert.ok(manifest.manifestSha256.match(/^[a-f0-9]{64}$/));
    await access(join(temporaryRoot, 'content', 'shared', 'content.css'));
    const statement = await readFile(
      join(temporaryRoot, ...manifest.problems[0].statement.assetPath.split('/')),
      'utf8',
    );
    assert.match(statement, /\.\.\/\.\.\/\.\.\/\.\.\/shared\/katex\/katex\.min\.css/);
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

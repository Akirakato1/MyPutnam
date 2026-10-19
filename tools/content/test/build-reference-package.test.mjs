import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { buildReferencePackage } from '../src/build-reference-package.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';
import { verifyPackageArchive } from '../src/package-archive.mjs';

const fixtureUrl = new URL('./fixtures/contracts/valid-reference.json', import.meta.url);
const libraryUrl = new URL('../../../content-src/library.json', import.meta.url);

test('reference package is deterministic, canonical, complete, and isolated', async (t) => {
  const assetRoot = await mkdtemp(join(tmpdir(), 'myputnam-reference-assets-'));
  t.after(() => rm(assetRoot, { recursive: true, force: true }));
  const [record, libraryCatalog] = await Promise.all([
    readJson(fixtureUrl), loadLibraryCatalog(libraryUrl),
  ]);
  const input = { record, articleAssetRoot: assetRoot, libraryCatalog, minAppVersionCode: 1 };
  const first = await buildReferencePackage(input);
  const second = await buildReferencePackage(input);

  assert.deepEqual(first, second);
  assert.equal(first.contentKey, 'math-reference/number-theory/euler-totient');
  assert.deepEqual(first.catalogMetadata, {
    key: first.contentKey,
    categoryId: 'number-theory', articleId: 'euler-totient',
    title: "Euler's totient function", displayOrder: 1,
    aliases: ['phi', 'totient'], keywords: ['coprime', 'residue classes'],
    rights: record.rights,
  });

  const verified = await verifyPackageArchive(first.archiveBytes, { expectedDescriptor: null });
  assert.equal(verified.envelope.contentType, 'reference');
  assert.equal(verified.envelope.contentSchemaVersion, 1);
  assert.equal(verified.envelope.richDocumentVersion, 2);
  assert.deepEqual([...verified.members.keys()], [
    'documents/article.fragment.html',
    'source/reference.json',
  ]);
  const source = Buffer.from(verified.members.get('source/reference.json')).toString('utf8');
  assert.deepEqual(JSON.parse(source), record);
  assert.match(source, /^\{"aliases":/);
  assert.doesNotMatch([...verified.members.keys()].join('\n'), /review|provenance|original|\.pdf|\.tex/i);
});

async function readJson(url) {
  return JSON.parse(await readFile(url, 'utf8'));
}

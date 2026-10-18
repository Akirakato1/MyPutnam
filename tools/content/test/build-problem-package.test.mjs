import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { buildProblemPackage } from '../src/build-problem-package.mjs';
import { verifyPackageArchive } from '../src/package-archive.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';

const sourceRoot = new URL('../../../content-src/', import.meta.url);
const libraryUrl = new URL('../../../content-src/library.json', import.meta.url);
const fixtureUrl = new URL('./fixtures/valid-problem.json', import.meta.url);
const codes = ['A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'B1', 'B2', 'B3', 'B4', 'B5', 'B6'];

test('fixture package contains exact inert documents, canonical source, and referenced assets only', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'myputnam-problem-package-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const assetRoot = join(root, 'A1');
  await mkdir(join(assetRoot, 'figures'), { recursive: true });
  await writeFile(join(assetRoot, 'figures', 'unit-square.svg'),
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path d="M1 1h8v8H1z"/></svg>');

  const [record, libraryCatalog] = await Promise.all([
    readJson(fixtureUrl), loadLibraryCatalog(libraryUrl),
  ]);
  record.statement.blocks.push({ type: 'figure', path: 'figures/unit-square.svg', alt: 'Unit square' });
  const result = await buildProblemPackage({
    record, problemAssetRoot: assetRoot, libraryCatalog, minAppVersionCode: 1,
  });
  assert.equal(result.contentKey, 'putnam/2025/A1');
  assert.deepEqual(result.catalogMetadata, {
    key: 'putnam/2025/A1', collectionId: 'putnam', setId: '2025', problemId: 'A1',
    label: 'A1', ordinal: 1, topics: ['algebra'],
  });

  const verified = await verifyPackageArchive(result.archiveBytes, { expectedDescriptor: null });
  assert.deepEqual([...verified.members.keys()], [
    'assets/figures/unit-square.svg',
    'documents/hint-1.fragment.html',
    'documents/hint-2.fragment.html',
    'documents/hint-3.fragment.html',
    'documents/hint-4.fragment.html',
    'documents/prerequisite-1.fragment.html',
    'documents/solution.fragment.html',
    'documents/statement.fragment.html',
    'source/problem.json',
  ]);
  const source = text(verified.members.get('source/problem.json'));
  assert.match(source, /^\{"collectionId":"putnam"/);
  assert.deepEqual(JSON.parse(source).hints.map(({ stage }) => stage), ['nudge', 'strategy', 'knowledge', 'outline']);
  for (const [path, bytes] of verified.members) {
    if (!path.endsWith('.fragment.html')) continue;
    assert.doesNotMatch(text(bytes), /<(?:html|head|body|script|style|base)\b/i, path);
  }
  assert.match(text(verified.members.get('documents/statement.fragment.html')), /src="assets\/figures\/unit-square\.svg"/);
  assert.equal([...verified.members.keys()].some((path) => /pdf|\.tex$|lock|review/i.test(path)), false);
});

test('real putnam/2025/A1 compiles deterministically with exact staged documents', async () => {
  const [record, libraryCatalog] = await Promise.all([
    readJson(new URL('../../../content-src/2025/problems/A1.json', import.meta.url)),
    loadLibraryCatalog(libraryUrl),
  ]);
  const input = {
    record,
    problemAssetRoot: new URL('../../../content-src/2025/problems/A1/', import.meta.url),
    libraryCatalog,
    minAppVersionCode: 1,
  };
  const first = await buildProblemPackage(input);
  const second = await buildProblemPackage(input);
  assert.deepEqual(first, second);
  const verified = await verifyPackageArchive(first.archiveBytes, { expectedDescriptor: null });
  assert.equal(verified.envelope.contentKey, 'putnam/2025/A1');
  assert.deepEqual([...verified.members.keys()].filter((path) => path.startsWith('documents/hint-')), [
    'documents/hint-1.fragment.html', 'documents/hint-2.fragment.html',
    'documents/hint-3.fragment.html', 'documents/hint-4.fragment.html',
  ]);
});

test('all 72 Putnam 2020-2025 packages are complete, isolated, unique, and byte-stable', async () => {
  const libraryCatalog = await loadLibraryCatalog(libraryUrl);
  const first = new Map();
  const second = new Map();
  for (const year of [2020, 2021, 2022, 2023, 2024, 2025]) {
    const found = [];
    for (const code of codes) {
      const record = await readJson(new URL(`../../../content-src/${year}/problems/${code}.json`, import.meta.url));
      const options = {
        record,
        problemAssetRoot: new URL(`../../../content-src/${year}/problems/${code}/`, import.meta.url),
        libraryCatalog,
        minAppVersionCode: 1,
      };
      const left = await buildProblemPackage(options);
      const right = await buildProblemPackage(options);
      found.push(record.problemId);
      assert.equal(first.has(left.contentKey), false, left.contentKey);
      first.set(left.contentKey, Buffer.from(left.archiveBytes));
      second.set(right.contentKey, Buffer.from(right.archiveBytes));
      assert.deepEqual(left.archiveBytes, right.archiveBytes, left.contentKey);

      const verified = await verifyPackageArchive(left.archiveBytes, { expectedDescriptor: null });
      for (const path of verified.members.keys()) {
        assert.doesNotMatch(path, /(?:^|\/)\.\.(?:\/|$)/, `${left.contentKey}: ${path}`);
        assert.equal(path.startsWith('assets/') || path.startsWith('documents/') || path === 'source/problem.json', true,
          `${left.contentKey}: ${path}`);
      }
    }
    assert.deepEqual(found, codes, String(year));
  }
  assert.equal(first.size, 72);
  assert.deepEqual([...first.keys()].sort(), [...second.keys()].sort());
});

async function readJson(url) {
  return JSON.parse(await readFile(url, 'utf8'));
}

function text(bytes) {
  return Buffer.from(bytes).toString('utf8');
}

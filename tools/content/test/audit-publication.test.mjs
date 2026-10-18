import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cp, mkdir, mkdtemp, readFile, rm, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';

import { auditPublication } from '../src/audit-publication.mjs';
import { buildPublication } from '../src/build-publication.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';
import { createPackageArchive, verifyPackageArchive } from '../src/package-archive.mjs';

const sourceRoot = new URL('../../../content-src/', import.meta.url);
const libraryUrl = new URL('../../../content-src/library.json', import.meta.url);
const publicationUrl = new URL('../../../content-src/publications/content-v1.json', import.meta.url);

test('independent audit accepts the local tree but the public rights gate remains closed', async (t) => {
  const root = await buildFixture(t);
  assert.deepEqual(await auditPublication(root), []);
  const rightsErrors = await auditPublication(root, { requireClearedRights: true });
  assert.equal(rightsErrors.length, 72);
  assert.ok(rightsErrors.every((error) => error.includes('redistributionStatus')));
});

test('independent audit detects catalog drift, corrupt, missing, orphaned, and renamed packages', async (t) => {
  const baseline = await buildFixture(t);
  const catalog = await readCatalog(baseline);
  const target = catalog.problems[0];
  const targetPath = packagePath(baseline, target.package.path);

  await withCopy(t, baseline, async (root) => {
    const value = await readCatalog(root);
    value.problems[0].key = 'putnam/2020/Z9';
    await writeCatalog(root, value);
    assert.ok((await auditPublication(root)).some((error) => /key|identity|content/i.test(error)));
  });
  await withCopy(t, baseline, async (root) => {
    const path = packagePath(root, target.package.path);
    const bytes = await readFile(path);
    bytes[Math.floor(bytes.length / 2)] ^= 0xff;
    await writeFile(path, bytes);
    assert.ok((await auditPublication(root)).some((error) => /sha256|hash|zip|integrity|payload/i.test(error)));
  });
  await withCopy(t, baseline, async (root) => {
    await unlink(packagePath(root, target.package.path));
    assert.ok((await auditPublication(root)).some((error) => /missing/i.test(error)));
  });
  await withCopy(t, baseline, async (root) => {
    await cp(targetPath, join(dirname(packagePath(root, target.package.path)), 'orphan.zip'));
    assert.ok((await auditPublication(root)).some((error) => /orphan/i.test(error)));
  });
  await withCopy(t, baseline, async (root) => {
    const value = await readCatalog(root);
    value.problems[0].package.path = value.problems[0].package.path.replace(/[^/]+\.zip$/, `0${target.package.sha256.slice(1)}.zip`);
    await cp(packagePath(root, target.package.path), packagePath(root, value.problems[0].package.path));
    await unlink(packagePath(root, target.package.path));
    await writeCatalog(root, value);
    assert.ok((await auditPublication(root)).some((error) => /immutable|filename|sha256|path/i.test(error)));
  });
});

test('independent audit rejects forbidden source/review/script/TeX/PDF artifacts anywhere', async (t) => {
  const baseline = await buildFixture(t);
  for (const name of ['original.pdf', 'source.tex', 'review.md', 'source-lock.json', 'inject.js']) {
    await withCopy(t, baseline, async (root) => {
      await writeFile(join(root, name), 'forbidden');
      assert.ok((await auditPublication(root)).some((error) => error.includes(name)), name);
    });
  }
});

test('independent audit detects envelope, source, revision, member, digest, and size drift separately', async (t) => {
  const baseline = await buildFixture(t);
  await withCopy(t, baseline, async (root) => {
    await replaceProblemPackage(root, ({ options }) => ({ ...options, contentKey: 'putnam/2020/Z9' }));
    assert.ok((await auditPublication(root)).some((error) => /content key/i.test(error)));
  });
  await withCopy(t, baseline, async (root) => {
    await replaceProblemPackage(root, ({ options, members }) => {
      const source = members.find(({ path }) => path === 'source/problem.json');
      const value = JSON.parse(Buffer.from(source.bytes).toString('utf8'));
      value.problemId = 'Z9';
      source.bytes = Buffer.from(JSON.stringify(value));
      return { ...options, members };
    });
    assert.ok((await auditPublication(root)).some((error) => /problemId|source record/i.test(error)));
  });
  await withCopy(t, baseline, async (root) => {
    await replaceProblemPackage(root, ({ options, members }) => ({
      ...options, members: members.filter(({ path }) => path !== 'documents/solution.fragment.html'),
    }));
    assert.ok((await auditPublication(root)).some((error) => /solution|logical member/i.test(error)));
  });
  for (const field of ['packageRevision', 'sha256', 'compressedBytes', 'uncompressedBytes']) {
    await withCopy(t, baseline, async (root) => {
      const value = await readCatalog(root);
      value.problems[0].package[field] = typeof value.problems[0].package[field] === 'number'
        ? value.problems[0].package[field] + 1 : '0'.repeat(64);
      await writeCatalog(root, value);
      assert.ok((await auditPublication(root)).some((error) =>
        error.toLocaleLowerCase('en-US').includes(field.toLocaleLowerCase('en-US'))), field);
    });
  }
});

async function buildFixture(t) {
  const parent = await mkdtemp(join(tmpdir(), 'myputnam-audit-publication-'));
  t.after(() => rm(parent, { recursive: true, force: true }));
  const root = join(parent, 'publication');
  const [libraryCatalog, publicationManifest] = await Promise.all([
    loadLibraryCatalog(libraryUrl), JSON.parse(await readFile(publicationUrl, 'utf8')),
  ]);
  await buildPublication({ sourceRoot, libraryCatalog, publicationManifest, outputRoot: root });
  return root;
}

async function withCopy(t, baseline, callback) {
  const parent = await mkdtemp(join(tmpdir(), 'myputnam-audit-mutation-'));
  t.after(() => rm(parent, { recursive: true, force: true }));
  const root = join(parent, 'publication');
  await cp(baseline, root, { recursive: true });
  await callback(root);
}

async function readCatalog(root) {
  return JSON.parse(await readFile(join(root, 'content', 'v1', 'catalog.json'), 'utf8'));
}

async function writeCatalog(root, value) {
  await writeFile(join(root, 'content', 'v1', 'catalog.json'), `${JSON.stringify(value, null, 2)}\n`);
}

function packagePath(root, relativePath) {
  return join(root, 'content', 'v1', ...relativePath.split('/'));
}

async function replaceProblemPackage(root, transform) {
  const catalog = await readCatalog(root);
  const problem = catalog.problems[0];
  const oldPath = packagePath(root, problem.package.path);
  const verified = await verifyPackageArchive(await readFile(oldPath), { expectedDescriptor: null });
  const declarationByPath = new Map(verified.envelope.members.map((item) => [item.path, item]));
  const members = [...verified.members].map(([path, bytes]) => ({
    path, mediaType: declarationByPath.get(path).mediaType, bytes: Buffer.from(bytes),
  }));
  const options = {
    contentType: verified.envelope.contentType,
    contentKey: verified.envelope.contentKey,
    sourceSchemaVersion: verified.envelope.contentSchemaVersion,
    richDocumentVersion: verified.envelope.richDocumentVersion,
    minAppVersionCode: verified.envelope.minAppVersionCode,
    members,
  };
  const replacement = await createPackageArchive(transform({ options, members }));
  const sha256 = createHash('sha256').update(replacement.bytes).digest('hex');
  const descriptor = {
    contentType: replacement.envelope.contentType,
    packageRevision: replacement.envelope.packageRevision,
    packageSchemaVersion: replacement.envelope.packageSchemaVersion,
    contentSchemaVersion: replacement.envelope.contentSchemaVersion,
    richDocumentVersion: replacement.envelope.richDocumentVersion,
    path: `packages/problem/${problem.collectionId}/${problem.setId}/${problem.problemId}/${sha256}.zip`,
    compressedBytes: replacement.bytes.byteLength,
    uncompressedBytes: replacement.envelope.declaredUncompressedBytes,
    sha256,
    minAppVersionCode: replacement.envelope.minAppVersionCode,
  };
  const newPath = packagePath(root, descriptor.path);
  await mkdir(dirname(newPath), { recursive: true });
  await writeFile(newPath, replacement.bytes);
  if (newPath !== oldPath) await unlink(oldPath);
  problem.package = descriptor;
  await writeCatalog(root, catalog);
}

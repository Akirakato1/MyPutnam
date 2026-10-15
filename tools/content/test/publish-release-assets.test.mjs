import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, relative } from 'node:path';
import test from 'node:test';

let publishContract = {};
try {
  publishContract = await import('../src/publish-release-assets.mjs');
} catch {
  // RED: the transactional publisher is introduced by this task.
}

test('publisher atomically replaces content and database and removes the obsolete year tree', async (t) => {
  assert.equal(typeof publishContract.publishReleaseAssets, 'function');
  const fixture = await createFixture(t);
  await publishContract.publishReleaseAssets(fixture.options);

  assert.equal(await readFile(join(fixture.destination, 'content', 'release.json'), 'utf8'), fixture.releaseText);
  assert.equal(await readFile(join(fixture.destination, 'databases', 'content.db'), 'utf8'), 'new database');
  await assert.rejects(readFile(join(fixture.destination, 'content', '2025', 'old.txt'), 'utf8'));
});

test('publisher rejects receipt drift and an active publication lock without changing baseline bytes', async (t) => {
  assert.equal(typeof publishContract.publishReleaseAssets, 'function');
  const drift = await createFixture(t);
  await writeFile(join(drift.staging, 'content', 'release.json'), '{}\n', 'utf8');
  await assert.rejects(publishContract.publishReleaseAssets(drift.options), /receipt|hash|changed/i);
  assert.equal(await readFile(join(drift.destination, 'databases', 'content.db'), 'utf8'), 'old database');

  const locked = await createFixture(t);
  await writeFile(`${locked.destination}.publish.lock`, 'locked', 'utf8');
  await assert.rejects(publishContract.publishReleaseAssets(locked.options), /publisher|lock|progress/i);
  assert.equal(await readFile(join(locked.destination, 'databases', 'content.db'), 'utf8'), 'old database');
});

test('publisher rolls content back when database replacement fails', async (t) => {
  assert.equal(typeof publishContract.publishReleaseAssets, 'function');
  const fixture = await createFixture(t);
  await assert.rejects(
    publishContract.publishReleaseAssets({
      ...fixture.options,
      onPhase(phase) {
        if (phase === 'content-replaced') throw new Error('injected database replacement failure');
      },
    }),
    /injected database replacement failure/,
  );
  assert.equal(await readFile(join(fixture.destination, 'content', '2025', 'old.txt'), 'utf8'), 'old content');
  assert.equal(await readFile(join(fixture.destination, 'databases', 'content.db'), 'utf8'), 'old database');
});

async function createFixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'putnam-publish-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const staging = join(root, 'staging');
  const destination = join(root, 'assets');
  const release = { schemaVersion: 1, releaseId: 'fixture' };
  const releaseText = `${JSON.stringify(release, null, 2)}\n`;
  await write(join(staging, 'content', 'release.json'), releaseText);
  await write(join(staging, 'content', 'library.json'), '{"schemaVersion":1}\n');
  await write(join(staging, 'databases', 'content.db'), 'new database');
  await write(join(destination, 'content', '2025', 'old.txt'), 'old content');
  await write(join(destination, 'databases', 'content.db'), 'old database');
  const entries = await fileEntries(staging);
  const receipt = {
    schemaVersion: 1,
    releaseId: 'fixture',
    expectedReleaseSha256: sha(Buffer.from(canonical(release))),
    projectedCatalogSha256: sha(Buffer.from(canonical({ schemaVersion: 1 }))),
    databaseSha256: entries.find(({ path }) => path === 'databases/content.db').sha256,
    assetTreeSha256: sha(Buffer.from(canonical(entries))),
    counts: {},
    files: entries,
  };
  const receiptPath = join(root, 'receipt.json');
  await writeFile(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`, 'utf8');
  return {
    staging,
    destination,
    releaseText,
    options: { stagingAssetRoot: staging, receiptPath, androidAssetRoot: destination },
  };
}

async function write(path, value) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, value, 'utf8');
}

async function fileEntries(root) {
  const { readdir } = await import('node:fs/promises');
  const output = [];
  async function visit(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) await visit(path);
      else {
        const bytes = await readFile(path);
        output.push({
          path: relative(root, path).replaceAll('\\', '/'),
          bytes: bytes.byteLength,
          sha256: sha(bytes),
        });
      }
    }
  }
  await visit(root);
  return output.sort((left, right) => left.path.localeCompare(right.path));
}

function sha(value) {
  return createHash('sha256').update(value).digest('hex');
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

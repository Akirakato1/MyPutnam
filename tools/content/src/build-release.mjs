import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import {
  access,
  cp,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

import { auditYear } from './audit-year.mjs';
import { buildSet } from './build-set.mjs';
import { loadArchiveCatalog } from './archive-catalog.mjs';
import { loadLibraryCatalog } from './library-catalog.mjs';
import { loadReleaseManifest, validateReleaseManifest } from './release-manifest.mjs';

const executeFile = promisify(execFile);
const databaseBuilder = fileURLToPath(new URL('./build-content-db.py', import.meta.url));

export async function buildRelease({
  archiveCatalog,
  libraryCatalog,
  releaseManifest,
  sourceRoot,
  stagingRoot,
  reviewRoot,
  receiptPath = join(dirname(toPath(stagingRoot)), 'bundle-receipt.json'),
}) {
  const validationErrors = validateReleaseManifest(releaseManifest, archiveCatalog, libraryCatalog);
  if (validationErrors.length > 0) {
    throw new Error(`Release manifest validation failed:\n${validationErrors.map((error) => `- ${error}`).join('\n')}`);
  }

  const roots = {
    source: resolve(toPath(sourceRoot)),
    staging: resolve(toPath(stagingRoot)),
    review: resolve(toPath(reviewRoot)),
    receipt: resolve(toPath(receiptPath)),
  };
  await assertEmptyOrAbsent(roots.staging, 'staging directory');
  await mkdir(roots.staging, { recursive: true });
  await rm(roots.review, { recursive: true, force: true });
  await mkdir(roots.review, { recursive: true });

  try {
    const projectedCatalog = projectCatalog(libraryCatalog, releaseManifest.includedSets);
    const manifests = [];
    let sharedSignature = null;
    for (const key of releaseManifest.includedSets) {
      const [collectionId, setId] = key.split('/');
      if (collectionId !== 'putnam' || !/^\d{4}$/.test(setId)) {
        throw new Error(`Unsupported release source mapping: ${key}`);
      }
      const year = Number(setId);
      const yearSource = join(roots.source, setId);
      const auditErrors = await auditYear(yearSource, year, { catalog: projectedCatalog });
      if (auditErrors.length > 0) {
        throw new Error(
          `Release source audit failed for ${key}:\n${auditErrors.map((error) => `- ${error}`).join('\n')}`,
        );
      }
      const setBuildRoot = join(roots.review, `${collectionId}-${setId}`);
      const scratchParent = await mkdtemp(join(tmpdir(), 'putnam-release-set-'));
      const scratchBuildRoot = join(scratchParent, 'output');
      try {
        const manifest = await buildSet(yearSource, scratchBuildRoot, {
          catalog: projectedCatalog,
          collectionId,
          setId,
          contentVersion: releaseManifest.contentVersion,
        });
        manifests.push(manifest);
        await cp(scratchBuildRoot, setBuildRoot, { recursive: true });

        const sharedAssets = manifest.assets.filter(({ path }) => path.startsWith('content/shared/'));
        const signature = canonicalJson(sharedAssets);
        if (sharedSignature === null) {
          sharedSignature = signature;
          await cp(
            join(scratchBuildRoot, 'content', 'shared'),
            join(roots.staging, 'content', 'shared'),
            { recursive: true },
          );
        } else if (signature !== sharedSignature) {
          throw new Error(`Generated shared assets differ for ${key}`);
        }
        await cp(
          join(scratchBuildRoot, 'content', collectionId, setId),
          join(roots.staging, 'content', collectionId, setId),
          { recursive: true },
        );
      } finally {
        await rm(scratchParent, { recursive: true, force: true });
      }
    }

    manifests.sort((left, right) =>
      `${left.collectionId}/${left.setId}`.localeCompare(`${right.collectionId}/${right.setId}`));
    assertReleaseManifests(releaseManifest, manifests);
    await mkdir(join(roots.staging, 'content'), { recursive: true });
    await Promise.all([
      writeFile(
        join(roots.staging, 'content', 'release.json'),
        `${JSON.stringify(releaseManifest, null, 2)}\n`,
        'utf8',
      ),
      writeFile(
        join(roots.staging, 'content', 'library.json'),
        `${JSON.stringify(projectedCatalog, null, 2)}\n`,
        'utf8',
      ),
    ]);

    const databasePath = join(roots.staging, 'databases', 'content.db');
    await mkdir(dirname(databasePath), { recursive: true });
    const manifestPaths = releaseManifest.includedSets.map((key) =>
      join(roots.staging, 'content', ...key.split('/'), 'manifest.json'));
    const python = process.env.PUTNAM_PYTHON ?? 'python';
    await executeFile(python, [
      databaseBuilder,
      ...manifestPaths.flatMap((path) => ['--manifest', path]),
      '--catalog', join(roots.staging, 'content', 'library.json'),
      '--release', join(roots.staging, 'content', 'release.json'),
      '--output', databasePath,
    ]);

    const summary = summarize(manifests, projectedCatalog);
    const receipt = await createBundleReceipt(roots.staging, releaseManifest, projectedCatalog, summary);
    await mkdir(dirname(roots.receipt), { recursive: true });
    await writeFile(roots.receipt, `${JSON.stringify(receipt, null, 2)}\n`, 'utf8');

    return {
      assetRoot: roots.staging,
      releaseManifestPath: join(roots.staging, 'content', 'release.json'),
      projectedLibraryCatalogPath: join(roots.staging, 'content', 'library.json'),
      setManifestPaths: manifestPaths,
      databasePath,
      receiptPath: roots.receipt,
      summary,
    };
  } catch (error) {
    await rm(roots.staging, { recursive: true, force: true });
    throw error;
  }
}

export async function createBundleReceipt(assetRoot, releaseManifest, projectedCatalog, counts) {
  const root = resolve(toPath(assetRoot));
  const files = await collectFiles(root);
  const database = files.find(({ path }) => path === 'databases/content.db');
  if (!database) throw new Error('Staged bundle is missing databases/content.db');
  return {
    schemaVersion: 1,
    releaseId: releaseManifest.releaseId,
    expectedReleaseSha256: sha256(Buffer.from(canonicalJson(releaseManifest), 'utf8')),
    projectedCatalogSha256: sha256(Buffer.from(canonicalJson(projectedCatalog), 'utf8')),
    databaseSha256: database.sha256,
    assetTreeSha256: sha256(Buffer.from(canonicalJson(files), 'utf8')),
    counts,
    files,
  };
}

function projectCatalog(catalog, includedSets) {
  const selected = new Set(includedSets);
  const collections = [];
  for (const collection of catalog.collections) {
    const sets = collection.sets.filter((item) => selected.has(`${collection.id}/${item.id}`));
    if (sets.length > 0) collections.push({ ...structuredClone(collection), sets: structuredClone(sets) });
  }
  return {
    schemaVersion: catalog.schemaVersion,
    contentSchemaVersion: catalog.contentSchemaVersion,
    collections,
    topics: structuredClone(catalog.topics),
  };
}

function assertReleaseManifests(release, manifests) {
  const keys = manifests.map(({ collectionId, setId }) => `${collectionId}/${setId}`);
  if (canonicalJson(keys) !== canonicalJson(release.includedSets)) {
    throw new Error(`Generated manifest sets do not match release selection: ${keys.join(', ')}`);
  }
  const problemKeys = new Set();
  for (const manifest of manifests) {
    if (manifest.problems.length !== 12) throw new Error(`${manifest.collectionId}/${manifest.setId} is partial`);
    for (const problem of manifest.problems) {
      if (problemKeys.has(problem.key)) throw new Error(`Duplicate problem key: ${problem.key}`);
      problemKeys.add(problem.key);
    }
  }
}

function summarize(manifests, catalog) {
  const problems = manifests.flatMap(({ problems }) => problems);
  return {
    collectionCount: catalog.collections.length,
    setCount: manifests.length,
    topicCount: catalog.topics.length,
    problemCount: problems.length,
    hintCount: problems.flatMap(({ hints }) => hints).length,
    prerequisiteCount: problems.flatMap(({ prerequisites }) => prerequisites).length,
  };
}

async function collectFiles(root) {
  const output = [];
  async function visit(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) await visit(path);
      else if (entry.isFile()) {
        const bytes = await readFile(path);
        output.push({
          path: relative(root, path).replaceAll('\\', '/'),
          bytes: bytes.byteLength,
          sha256: sha256(bytes),
        });
      }
    }
  }
  await visit(root);
  return output;
}

async function assertEmptyOrAbsent(path, label) {
  try {
    if (!(await stat(path)).isDirectory()) throw new Error(`${label} already exists and is not a directory`);
    if ((await readdir(path)).length > 0) throw new Error(`${label} must be empty before building`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function canonicalJson(value) {
  return JSON.stringify(canonicalize(value));
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]),
    );
  }
  return value;
}

function toPath(value) {
  return value instanceof URL ? fileURLToPath(value) : value;
}

function parseArguments(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 1) {
    const [name, inlineValue] = args[index].split('=', 2);
    const takeValue = () => inlineValue ?? args[++index];
    if (name === '--source') options.sourceRoot = takeValue();
    else if (name === '--archive-catalog') options.archiveCatalogPath = takeValue();
    else if (name === '--library-catalog') options.libraryCatalogPath = takeValue();
    else if (name === '--release') options.releasePath = takeValue();
    else if (name === '--staging') options.stagingRoot = takeValue();
    else if (name === '--review-output') options.reviewRoot = takeValue();
    else if (name === '--receipt') options.receiptPath = takeValue();
    else throw new Error(`Unknown argument: ${args[index]}`);
  }
  for (const key of [
    'sourceRoot', 'archiveCatalogPath', 'libraryCatalogPath', 'releasePath',
    'stagingRoot', 'reviewRoot', 'receiptPath',
  ]) {
    if (!options[key]) throw new Error(`Missing required release-builder option: ${key}`);
  }
  return options;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const [archiveCatalog, libraryCatalog] = await Promise.all([
    loadArchiveCatalog(options.archiveCatalogPath),
    loadLibraryCatalog(options.libraryCatalogPath),
  ]);
  const releaseManifest = await loadReleaseManifest(
    options.releasePath,
    archiveCatalog,
    libraryCatalog,
  );
  const result = await buildRelease({ ...options, archiveCatalog, libraryCatalog, releaseManifest });
  process.stdout.write(
    `Built ${result.summary.problemCount} problems across ${result.summary.setCount} sets at ${result.assetRoot}\n`,
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();

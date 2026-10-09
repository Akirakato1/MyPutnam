import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import {
  cp,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rename,
  rm,
  writeFile,
} from 'node:fs/promises';
import {
  basename,
  dirname,
  isAbsolute,
  join,
  relative,
  resolve,
  sep,
} from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

import {
  assertFinalArchiveComplete,
  loadArchiveCatalog,
} from './archive-catalog.mjs';
import { auditYear } from './audit-year.mjs';
import { buildYear } from './build-year.mjs';
import { loadLibraryCatalog } from './library-catalog.mjs';

const executeFile = promisify(execFile);
const databaseBuilder = fileURLToPath(new URL('./build-content-db.py', import.meta.url));

export async function buildArchive({
  archiveCatalog,
  libraryCatalog,
  sourceRoot,
  outputRoot,
  reviewRoot,
}) {
  assertFinalArchiveComplete(archiveCatalog);
  const roots = {
    source: resolve(sourceRoot),
    output: resolve(outputRoot),
    review: resolve(reviewRoot),
  };
  assertSeparateSafeRoots(roots);
  await assertSourceYears(roots.source, archiveCatalog.targetYears);

  const auditErrors = [];
  for (const year of archiveCatalog.targetYears) {
    for (const error of await auditYear(join(roots.source, String(year)), year, { catalog: libraryCatalog })) {
      auditErrors.push(`${year}/${error}`);
    }
  }
  if (auditErrors.length > 0) {
    throw new Error(
      'Archive source audit failed:\n'
        + auditErrors.sort((left, right) => left.localeCompare(right))
          .map((error) => `- ${error}`).join('\n'),
    );
  }

  await Promise.all([
    mkdir(dirname(roots.output), { recursive: true }),
    mkdir(dirname(roots.review), { recursive: true }),
  ]);
  let outputStaging = await mkdtemp(join(dirname(roots.output), `.${basename(roots.output)}.tmp-`));
  let reviewStaging = await mkdtemp(join(dirname(roots.review), `.${basename(roots.review)}.tmp-`));
  try {
    const manifests = [];
    let sharedSignature = null;
    let sharedAssets = [];
    for (const year of archiveCatalog.targetYears) {
      const yearRoot = join(reviewStaging, String(year));
      const manifest = await buildYear(join(roots.source, String(year)), yearRoot, {
        year,
        catalog: libraryCatalog,
        contentVersion: archiveCatalog.contentVersion,
      });
      manifests.push(manifest);
      const currentShared = manifest.assets
        .filter(({ path }) => path.startsWith('content/shared/'))
        .sort((left, right) => left.path.localeCompare(right.path));
      const signature = canonicalJson(currentShared);
      if (sharedSignature === null) {
        sharedSignature = signature;
        sharedAssets = currentShared;
        await cp(join(yearRoot, 'content', 'shared'), join(outputStaging, 'content', 'shared'), { recursive: true });
      } else if (signature !== sharedSignature) {
        throw new Error('Generated shared assets differ between archive years');
      }
      await cp(
        join(yearRoot, 'content', 'putnam', String(year)),
        join(outputStaging, 'content', 'putnam', String(year)),
        { recursive: true },
      );
    }
    manifests.sort((left, right) => left.setId.localeCompare(right.setId));
    assertArchiveManifests(archiveCatalog, manifests);
    const manifestReferences = manifests.map((manifest) => ({
      collectionId: manifest.collectionId,
      setId: manifest.setId,
      path: `content/${manifest.collectionId}/${manifest.setId}/manifest.json`,
      sha256: manifest.manifestSha256,
    }));
    const archiveManifest = {
      schemaVersion: 3,
      contentVersion: archiveCatalog.contentVersion,
      catalogSha256: sha256(Buffer.from(canonicalJson(libraryCatalog), 'utf8')),
      archiveSha256: sha256(Buffer.from(canonicalJson({ archiveCatalog, manifests }), 'utf8')),
      years: [...archiveCatalog.targetYears],
      problemCount: manifests.reduce((count, manifest) => count + manifest.problems.length, 0),
      manifests: manifestReferences,
      sharedAssets,
    };
    await mkdir(join(outputStaging, 'content'), { recursive: true });
    await writeFile(
      join(outputStaging, 'content', 'archive-manifest.json'),
      `${JSON.stringify(archiveManifest, null, 2)}\n`,
      'utf8',
    );

    await rm(roots.output, { recursive: true, force: true });
    await rename(outputStaging, roots.output);
    outputStaging = null;
    await rm(roots.review, { recursive: true, force: true });
    await rename(reviewStaging, roots.review);
    reviewStaging = null;
    return { archiveManifest, manifests };
  } finally {
    if (outputStaging) await rm(outputStaging, { recursive: true, force: true });
    if (reviewStaging) await rm(reviewStaging, { recursive: true, force: true });
  }
}

export function assertUniqueProblemKeys(manifests) {
  const seen = new Set();
  for (const manifest of manifests) {
    for (const problem of manifest.problems ?? []) {
      const key = String(problem.key);
      if (seen.has(key)) {
        throw new Error(`Duplicate problem key in archive manifests: ${key}`);
      }
      seen.add(key);
    }
  }
}

function assertArchiveManifests(catalog, manifests) {
  const years = manifests.map(({ setId }) => Number.parseInt(setId, 10));
  if (canonicalJson(years) !== canonicalJson(catalog.targetYears)) {
    throw new Error(
      'Archive manifest years do not match catalog target years: '
        + canonicalJson(years),
    );
  }

  for (const manifest of manifests) {
    if (manifest.collectionId !== 'putnam') {
      throw new Error(`Archive manifest collection must be putnam: ${manifest.collectionId}`);
    }
    for (const problem of manifest.problems ?? []) {
      if (problem.collectionId !== manifest.collectionId || problem.setId !== manifest.setId) {
        throw new Error(
          `Problem ${problem.key} does not match manifest ${manifest.collectionId}/${manifest.setId}`,
        );
      }
    }
  }
  assertUniqueProblemKeys(manifests);
}

async function consolidateArchive(catalog, manifests, reviewRoot, stagingRoot) {
  const sharedAssets = new Map();
  let expectedSharedSignature = null;
  const normalizedManifests = [];
  const manifestReferences = [];

  for (const manifest of manifests) {
    const year = manifest.year;
    const yearReviewRoot = join(reviewRoot, String(year));
    const yearOutputRoot = join(stagingRoot, String(year));
    await mkdir(yearOutputRoot, { recursive: true });
    await cp(
      join(yearReviewRoot, 'problems'),
      join(yearOutputRoot, 'problems'),
      { recursive: true },
    );

    const shared = manifest.assets
      .filter(({ path }) => path.startsWith('shared/'))
      .sort((left, right) => left.path.localeCompare(right.path));
    const signature = canonicalJson(shared);
    if (expectedSharedSignature === null) {
      expectedSharedSignature = signature;
    } else if (signature !== expectedSharedSignature) {
      throw new Error('Generated shared assets differ between archive years');
    }

    for (const asset of shared) {
      const existing = sharedAssets.get(asset.path);
      if (existing && (existing.sha256 !== asset.sha256 || existing.bytes !== asset.bytes)) {
        throw new Error('Shared asset collision at ' + asset.path);
      }
      if (!existing) {
        const destination = join(stagingRoot, ...asset.path.split('/'));
        await mkdir(dirname(destination), { recursive: true });
        await cp(join(yearReviewRoot, ...asset.path.split('/')), destination);
        sharedAssets.set(asset.path, { ...asset });
      }
    }

    const normalizedManifest = await normalizeYearManifest(
      manifest,
      yearOutputRoot,
    );
    const manifestBytes = Buffer.from(
      JSON.stringify(normalizedManifest, null, 2) + '\n',
      'utf8',
    );
    await writeFile(join(yearOutputRoot, 'manifest.json'), manifestBytes);
    normalizedManifests.push(normalizedManifest);
    manifestReferences.push({
      year,
      path: String(year) + '/manifest.json',
      sha256: sha256(manifestBytes),
    });
  }

  const catalogSha256 = sha256(Buffer.from(canonicalJson(catalog), 'utf8'));
  const archiveSha256 = sha256(Buffer.from(canonicalJson({
    catalog,
    manifests: normalizedManifests,
  }), 'utf8'));
  const archiveManifest = {
    schemaVersion: catalog.contentSchemaVersion,
    contentVersion: catalog.contentVersion,
    catalogSha256,
    archiveSha256,
    years: [...catalog.targetYears],
    problemCount: normalizedManifests.reduce(
      (count, manifest) => count + manifest.problems.length,
      0,
    ),
    manifests: manifestReferences,
    sharedAssets: [...sharedAssets.values()]
      .sort((left, right) => left.path.localeCompare(right.path)),
  };
  await writeFile(
    join(stagingRoot, 'archive-manifest.json'),
    JSON.stringify(archiveManifest, null, 2) + '\n',
    'utf8',
  );
  return { archiveManifest, manifests: normalizedManifests };
}

async function normalizeYearManifest(manifest, yearOutputRoot) {
  const normalized = structuredClone(manifest);
  const localAssets = [];
  const assetsByPath = new Map();

  for (const asset of normalized.assets
    .filter(({ path }) => !path.startsWith('shared/'))
    .sort((left, right) => left.path.localeCompare(right.path))) {
    const path = join(yearOutputRoot, ...asset.path.split('/'));
    let bytes = await readFile(path);
    if (asset.path.endsWith('.html')) {
      const html = bytes.toString('utf8').replaceAll(
        'href="../../shared/',
        'href="../../../shared/',
      );
      bytes = Buffer.from(html, 'utf8');
      await writeFile(path, bytes);
    }
    const descriptor = {
      path: asset.path,
      sha256: sha256(bytes),
      bytes: bytes.byteLength,
    };
    localAssets.push(descriptor);
    assetsByPath.set(descriptor.path, descriptor);
  }

  normalized.assets = localAssets;
  for (const problem of normalized.problems) {
    problem.statement = refreshReference(problem.statement, assetsByPath);
    problem.solution = refreshReference(problem.solution, assetsByPath);
    problem.hints = problem.hints.map((hint) => ({
      ...hint,
      ...refreshReference(hint, assetsByPath),
    }));
    problem.prerequisites = problem.prerequisites.map((prerequisite) => ({
      ...prerequisite,
      ...refreshReference(prerequisite, assetsByPath),
    }));
  }
  return normalized;
}

function refreshReference(reference, assetsByPath) {
  const asset = assetsByPath.get(reference.assetPath);
  if (!asset) {
    throw new Error('Manifest references missing generated asset ' + reference.assetPath);
  }
  return {
    ...reference,
    sha256: asset.sha256,
  };
}

async function assertSourceYears(sourceRoot, targetYears) {
  const entries = await readdir(sourceRoot, { withFileTypes: true });
  const actualYears = entries
    .filter((entry) => entry.isDirectory() && /^\d{4}$/.test(entry.name))
    .map((entry) => Number.parseInt(entry.name, 10))
    .sort((left, right) => left - right);
  const expected = new Set(targetYears);
  const actual = new Set(actualYears);
  const missing = targetYears.filter((year) => !actual.has(year));
  const extra = actualYears.filter((year) => !expected.has(year));
  if (missing.length > 0 || extra.length > 0) {
    const details = [];
    if (missing.length > 0) {
      details.push('missing years: ' + missing.join(', '));
    }
    if (extra.length > 0) {
      details.push('extra years: ' + extra.join(', '));
    }
    throw new Error('Archive source years do not match catalog (' + details.join('; ') + ')');
  }
}

function assertSeparateSafeRoots({ source, output, review }) {
  const filesystemRoot = resolve(output, sep);
  if (output === filesystemRoot || review === resolve(review, sep)) {
    throw new Error('Archive output and review roots must not be filesystem roots');
  }

  const pairs = [
    ['source', source, 'output', output],
    ['source', source, 'review', review],
    ['output', output, 'review', review],
  ];
  for (const [leftName, left, rightName, right] of pairs) {
    if (isWithin(left, right) || isWithin(right, left)) {
      throw new Error(
        'Archive ' + leftName + ' and ' + rightName
          + ' roots must be separate and non-overlapping',
      );
    }
  }
}

function isWithin(root, candidate) {
  const pathFromRoot = relative(root, candidate);
  return pathFromRoot === ''
    || (!pathFromRoot.startsWith('..' + sep)
      && pathFromRoot !== '..'
      && !isAbsolute(pathFromRoot));
}

function canonicalJson(value) {
  return JSON.stringify(canonicalize(value));
}

function canonicalize(value) {
  if (Array.isArray(value)) {
    return value.map(canonicalize);
  }
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value).sort((left, right) => left.localeCompare(right))
        .map((key) => [key, canonicalize(value[key])]),
    );
  }
  return value;
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function parseArguments(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    const split = argument.indexOf('=');
    const name = split === -1 ? argument : argument.slice(0, split);
    const inlineValue = split === -1 ? undefined : argument.slice(split + 1);
    const takeValue = () => inlineValue ?? args[++index];
    if (name === '--source') {
      options.source = takeValue();
    } else if (name === '--output') {
      options.output = takeValue();
    } else if (name === '--review') {
      options.review = takeValue();
    } else if (name === '--archive-catalog' || name === '--catalog') {
      options.archiveCatalog = takeValue();
    } else if (name === '--library-catalog') {
      options.libraryCatalog = takeValue();
    } else if (name === '--database') {
      options.database = takeValue();
    } else {
      throw new Error('Unknown argument: ' + argument);
    }
  }

  for (const name of ['source', 'output', 'review', 'archiveCatalog', 'libraryCatalog', 'database']) {
    if (!options[name]) {
      throw new Error('--' + name + ' is required');
    }
  }
  return options;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const archiveCatalog = await loadArchiveCatalog(options.archiveCatalog);
  const libraryCatalog = await loadLibraryCatalog(options.libraryCatalog);
  const result = await buildArchive({
    archiveCatalog,
    libraryCatalog,
    sourceRoot: options.source,
    outputRoot: options.output,
    reviewRoot: options.review,
  });
  const manifestPaths = result.archiveManifest.manifests.map(
    ({ path }) => join(resolve(options.output), ...path.split('/')),
  );
  const python = process.env.PUTNAM_PYTHON ?? 'python';
  const databaseArguments = [
    databaseBuilder,
    '--catalog',
    resolve(options.libraryCatalog),
    '--archive-catalog',
    resolve(options.archiveCatalog),
  ];
  for (const manifest of manifestPaths) {
    databaseArguments.push('--manifest', manifest);
  }
  databaseArguments.push('--output', resolve(options.database));
  await executeFile(python, databaseArguments);
  process.stdout.write(
    'Built ' + result.archiveManifest.problemCount + ' problems across '
      + result.archiveManifest.years.length + ' years\n',
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}

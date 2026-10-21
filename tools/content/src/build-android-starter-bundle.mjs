import { createHash } from 'node:crypto';
import { cp, copyFile, mkdir, mkdtemp, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { auditPublication } from './audit-publication.mjs';

const CONTENT_TOOL_ROOT = resolve(import.meta.dirname, '..');
const RELEASE_FIELDS = new Set([
  'schemaVersion', 'releaseId', 'distributionScope', 'appVersionName', 'appVersionCode',
  'catalogId', 'catalogRevision', 'catalogSha256', 'generatedAt', 'feedRoot',
  'minAppVersionCode', 'supportedSchemaVersions', 'includedSets',
  'includedReferenceCategories', 'expectedProblemCount', 'expectedReferenceCount',
  'expectedPackageCount', 'allowedUnresolvedContentKeys',
]);

export async function buildAndroidStarterBundle({ publicationRoot, releaseManifest, outputRoot }) {
  const publicationPath = resolve(toPath(publicationRoot));
  const outputPath = resolve(toPath(outputRoot));
  await assertEmptyOrAbsent(outputPath);

  const auditErrors = await auditPublication(publicationPath);
  if (auditErrors.length) throw new Error(`Invalid publication:\n${formatErrors(auditErrors)}`);

  const catalogPath = join(publicationPath, 'content', 'v1', 'catalog.json');
  const catalogBytes = await readFile(catalogPath);
  const catalog = JSON.parse(catalogBytes);
  validateReleaseManifest(releaseManifest, catalog, catalogBytes);

  if (releaseManifest.distributionScope === 'public-release') {
    const rightsErrors = await auditPublication(publicationPath, { requireClearedRights: true });
    if (rightsErrors.length) {
      throw new Error(`public-release requires cleared rights for every catalog item:\n${formatErrors(rightsErrors)}`);
    }
  }

  const selectedSetKeys = new Set(releaseManifest.includedSets.map(({ collectionId, setId }) => `${collectionId}/${setId}`));
  const selectedCategories = new Set(releaseManifest.includedReferenceCategories);
  const selectedProblems = catalog.problems.filter(({ collectionId, setId }) => selectedSetKeys.has(`${collectionId}/${setId}`));
  const selectedReferences = catalog.references.filter(({ categoryId }) => selectedCategories.has(categoryId));
  const descriptors = [...selectedProblems, ...selectedReferences].map(({ package: descriptor }) => descriptor);

  await mkdir(dirname(outputPath), { recursive: true });
  const stagingRoot = await mkdtemp(join(dirname(outputPath), `.${basename(outputPath)}.tmp-`));
  try {
    await writeFile(join(stagingRoot, 'catalog.json'), catalogBytes);
    await writeFile(join(stagingRoot, 'release.json'), `${JSON.stringify(releaseManifest, null, 2)}\n`, 'utf8');
    await mkdir(join(stagingRoot, 'packages'), { recursive: true });
    const seenDigests = new Set();
    for (const descriptor of descriptors) {
      if (seenDigests.has(descriptor.sha256)) throw new Error(`Duplicate selected package digest: ${descriptor.sha256}`);
      seenDigests.add(descriptor.sha256);
      const source = join(publicationPath, 'content', 'v1', ...descriptor.path.split('/'));
      await copyFile(source, join(stagingRoot, 'packages', `${descriptor.sha256}.zip`));
    }
    await copyRenderer(stagingRoot);
    await rm(outputPath, { recursive: true, force: true });
    await rename(stagingRoot, outputPath);
  } catch (error) {
    await rm(stagingRoot, { recursive: true, force: true });
    throw error;
  }

  return {
    catalogSha256: sha256(catalogBytes),
    packageCount: descriptors.length,
    problemCount: selectedProblems.length,
    referenceCount: selectedReferences.length,
  };
}

function validateReleaseManifest(manifest, catalog, catalogBytes) {
  const errors = [];
  if (!isPlainObject(manifest)) throw new Error('Invalid release manifest: expected an object');
  for (const field of Object.keys(manifest)) if (!RELEASE_FIELDS.has(field)) errors.push(`unknown field ${field}`);
  for (const field of RELEASE_FIELDS) if (!(field in manifest)) errors.push(`missing ${field}`);
  if (manifest.schemaVersion !== 1) errors.push('schemaVersion must equal 1');
  if (!/^[a-z0-9][a-z0-9.-]*$/.test(manifest.releaseId ?? '')) errors.push('releaseId must be a safe identifier');
  if (!['local-development', 'public-release'].includes(manifest.distributionScope)) {
    errors.push('distributionScope must be local-development or public-release');
  }
  if (typeof manifest.appVersionName !== 'string' || manifest.appVersionName.length === 0) errors.push('appVersionName is required');
  if (!Number.isInteger(manifest.appVersionCode) || manifest.appVersionCode < 1) errors.push('appVersionCode must be a positive integer');
  if (manifest.catalogId !== catalog.catalogId) errors.push('catalogId does not match catalog');
  if (manifest.catalogRevision !== catalog.catalogRevision) errors.push('catalogRevision does not match catalog');
  if (manifest.catalogSha256 !== sha256(catalogBytes)) errors.push('catalogSha256 does not match catalog bytes');
  if (manifest.generatedAt !== catalog.generatedAt) errors.push('generatedAt does not match catalog');
  if (!isHttpsDirectoryUrl(manifest.feedRoot)) errors.push('feedRoot must be an HTTPS directory URL');
  if (manifest.minAppVersionCode !== catalog.minAppVersionCode) errors.push('minAppVersionCode does not match catalog');
  if (Number.isInteger(manifest.appVersionCode) && manifest.appVersionCode < catalog.minAppVersionCode) {
    errors.push('appVersionCode is lower than catalog minAppVersionCode');
  }

  const includedSets = Array.isArray(manifest.includedSets) ? manifest.includedSets : [];
  const setKeys = includedSets.map((item) => `${item?.collectionId}/${item?.setId}`);
  addDuplicateErrors(setKeys, 'includedSets', errors);
  const knownSets = new Set(catalog.problems.map(({ collectionId, setId }) => `${collectionId}/${setId}`));
  for (const key of setKeys) if (!knownSets.has(key)) errors.push(`includedSets contains unknown set ${key}`);

  const categories = Array.isArray(manifest.includedReferenceCategories) ? manifest.includedReferenceCategories : [];
  addDuplicateErrors(categories, 'includedReferenceCategories', errors);
  const knownCategories = new Set(catalog.referenceCategories.map(({ id }) => id));
  for (const id of categories) if (!knownCategories.has(id)) errors.push(`includedReferenceCategories contains unknown category ${id}`);

  const selectedProblems = catalog.problems.filter(({ collectionId, setId }) => setKeys.includes(`${collectionId}/${setId}`));
  const selectedReferences = catalog.references.filter(({ categoryId }) => categories.includes(categoryId));
  checkCount(manifest.expectedProblemCount, selectedProblems.length, 'expectedProblemCount', errors);
  checkCount(manifest.expectedReferenceCount, selectedReferences.length, 'expectedReferenceCount', errors);
  checkCount(manifest.expectedPackageCount, selectedProblems.length + selectedReferences.length, 'expectedPackageCount', errors);

  const unresolved = [...catalog.problems, ...catalog.references]
    .filter(({ rights }) => rights.redistributionStatus !== 'cleared')
    .map(({ key }) => key)
    .sort();
  const allowed = Array.isArray(manifest.allowedUnresolvedContentKeys)
    ? [...manifest.allowedUnresolvedContentKeys].sort()
    : [];
  addDuplicateErrors(allowed, 'allowedUnresolvedContentKeys', errors);
  const restricted = [...catalog.problems, ...catalog.references]
    .filter(({ rights }) => rights.redistributionStatus === 'restricted')
    .map(({ key }) => key);
  if (restricted.length) errors.push(`restricted content cannot be bundled: ${restricted.join(', ')}`);
  if (manifest.distributionScope === 'local-development' && !sameStrings(allowed, unresolved)) {
    errors.push('allowedUnresolvedContentKeys does not match the catalog unresolved-rights set');
  }
  if (manifest.distributionScope === 'public-release' && allowed.length) {
    errors.push('public-release cannot allow unresolved content keys');
  }

  validateSupportedVersions(manifest.supportedSchemaVersions, catalog, errors);
  if (errors.length) throw new Error(`Invalid release manifest:\n${formatErrors(errors)}`);
}

function validateSupportedVersions(supported, catalog, errors) {
  if (!isPlainObject(supported)) {
    errors.push('supportedSchemaVersions must be an object');
    return;
  }
  const expected = new Set(['catalog', 'package', 'problem', 'reference', 'richDocument']);
  for (const key of Object.keys(supported)) if (!expected.has(key)) errors.push(`supportedSchemaVersions has unknown field ${key}`);
  for (const key of expected) {
    if (!Array.isArray(supported[key]) || supported[key].some((value) => !Number.isInteger(value) || value < 1)) {
      errors.push(`supportedSchemaVersions.${key} must be a nonempty positive-integer array`);
    } else if (supported[key].length === 0) errors.push(`supportedSchemaVersions.${key} must not be empty`);
  }
  if (!Array.isArray(supported.catalog)) return;
  if (!supported.catalog.includes(catalog.schemaVersion)) errors.push(`unsupported catalog schema version ${catalog.schemaVersion}`);
  for (const entry of [...catalog.problems, ...catalog.references]) {
    const descriptor = entry.package;
    if (!supported.package?.includes(descriptor.packageSchemaVersion)) {
      errors.push(`${entry.key}: unsupported package schema version ${descriptor.packageSchemaVersion}`);
    }
    const typeVersions = descriptor.contentType === 'problem' ? supported.problem : supported.reference;
    if (!typeVersions?.includes(descriptor.contentSchemaVersion)) {
      errors.push(`${entry.key}: unsupported ${descriptor.contentType} content schema version ${descriptor.contentSchemaVersion}`);
    }
    if (!supported.richDocument?.includes(descriptor.richDocumentVersion)) {
      errors.push(`${entry.key}: unsupported rich-document version ${descriptor.richDocumentVersion}`);
    }
  }
}

async function copyRenderer(stagingRoot) {
  const rendererRoot = join(stagingRoot, 'renderer');
  const katexSource = join(CONTENT_TOOL_ROOT, 'node_modules', 'katex', 'dist');
  await mkdir(join(rendererRoot, 'katex'), { recursive: true });
  await copyFile(join(CONTENT_TOOL_ROOT, 'templates', 'document.html'), join(rendererRoot, 'document.html'));
  await copyFile(join(CONTENT_TOOL_ROOT, 'templates', 'content.css'), join(rendererRoot, 'content.css'));
  await copyFile(join(katexSource, 'katex.min.css'), join(rendererRoot, 'katex', 'katex.min.css'));
  await cp(join(katexSource, 'fonts'), join(rendererRoot, 'katex', 'fonts'), { recursive: true });
}

async function assertEmptyOrAbsent(path) {
  try {
    const entries = await readdir(path);
    if (entries.length > 0) throw new Error(`Starter bundle output root must be empty: ${path}`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
}

function checkCount(actual, expected, field, errors) {
  if (!Number.isInteger(actual) || actual !== expected) errors.push(`${field} must equal ${expected}`);
}

function addDuplicateErrors(values, field, errors) {
  const seen = new Set();
  for (const value of values) {
    if (seen.has(value)) errors.push(`duplicate ${field} value ${value}`);
    seen.add(value);
  }
}

function sameStrings(left, right) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isHttpsDirectoryUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.username === '' && url.password === '' && url.search === '' && url.hash === '' && url.pathname.endsWith('/');
  } catch {
    return false;
  }
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function formatErrors(errors) {
  return errors.map((error) => `- ${error}`).join('\n');
}

function toPath(value) {
  return value instanceof URL ? fileURLToPath(value) : value;
}

async function main(argv) {
  const args = parseArgs(argv);
  const releaseManifest = JSON.parse(await readFile(args.release, 'utf8'));
  const result = await buildAndroidStarterBundle({
    publicationRoot: args.publication,
    releaseManifest,
    outputRoot: args.output,
  });
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    const value = argv[index + 1];
    if (!name?.startsWith('--') || value === undefined) throw new Error(`Invalid argument: ${name ?? '(missing)'}`);
    args[name.slice(2)] = value;
  }
  for (const name of ['publication', 'release', 'output']) if (!args[name]) throw new Error(`Missing --${name}`);
  return args;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.stack ?? error.message}\n`);
    process.exitCode = 1;
  });
}

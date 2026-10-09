import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import {
  access,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  realpath,
  rename,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

import { isSafeLocalPath, problemKey, SCHEMA_VERSION } from './content-types.mjs';
import { findCatalogSet, loadLibraryCatalog, validateLibraryCatalog } from './library-catalog.mjs';
import { renderDocument } from './render-document.mjs';
import { validateProblem } from './validate-problem.mjs';

const contentRoot = fileURLToPath(new URL('..', import.meta.url));
const executeFile = promisify(execFile);
const databaseBuilder = fileURLToPath(new URL('./build-content-db.py', import.meta.url));

export async function buildSet(sourceDir, assetRoot, options) {
  const sourceRoot = resolve(sourceDir);
  const outputRoot = resolve(assetRoot);
  assertSeparateTrees(sourceRoot, outputRoot);
  const { catalog, collectionId, setId } = options ?? {};
  const catalogErrors = validateLibraryCatalog(catalog);
  if (catalogErrors.length > 0) {
    throw new Error(`Library catalog validation failed:\n${catalogErrors.map((error) => `- ${error}`).join('\n')}`);
  }
  const membership = findCatalogSet(catalog, collectionId, setId);
  if (!membership) throw new Error(`Unknown catalog set: ${collectionId}/${setId}`);

  const records = await loadRecords(sourceRoot, options.problems);
  const errors = validateRecords(records, catalog, collectionId, setId);
  if (errors.length > 0) {
    throw new Error(`Content validation failed:\n${errors.map((error) => `- ${error}`).join('\n')}`);
  }

  const parent = dirname(outputRoot);
  await mkdir(parent, { recursive: true });
  const stagingRoot = await mkdtemp(join(parent, `.${basename(outputRoot)}.tmp-`));
  try {
    const assets = new Map();
    await copySharedAssets(stagingRoot, assets);
    const problems = [];
    for (const { fileName, record } of records) {
      problems.push(await buildProblem(
        sourceRoot,
        stagingRoot,
        record,
        fileName,
        assets,
        membership,
      ));
    }
    problems.sort(compareProblems);
    const unsignedManifest = {
      schemaVersion: SCHEMA_VERSION,
      contentVersion: options.contentVersion,
      collectionId,
      setId,
      problems,
      assets: [...assets.values()].sort((left, right) => left.path.localeCompare(right.path)),
      catalogSha256: sha256(Buffer.from(canonicalJson(catalog), 'utf8')),
    };
    if (typeof unsignedManifest.contentVersion !== 'string' || !unsignedManifest.contentVersion.trim()) {
      throw new Error('contentVersion must be a non-empty string');
    }
    const manifest = {
      ...unsignedManifest,
      manifestSha256: sha256(Buffer.from(canonicalJson(unsignedManifest), 'utf8')),
    };
    const manifestPath = `content/${collectionId}/${setId}/manifest.json`;
    await writeGeneratedFile(stagingRoot, manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

    await rm(outputRoot, { recursive: true, force: true });
    await rename(stagingRoot, outputRoot);
    return manifest;
  } catch (error) {
    await rm(stagingRoot, { recursive: true, force: true });
    throw error;
  }
}

async function loadRecords(sourceRoot, requestedProblems) {
  const problemsRoot = join(sourceRoot, 'problems');
  const requested = requestedProblems ? new Set(requestedProblems) : null;
  const entries = (await readdir(problemsRoot, { withFileTypes: true }))
    .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
    .sort((left, right) => left.name.localeCompare(right.name));
  const records = [];
  const errors = [];
  for (const entry of entries) {
    const id = entry.name.slice(0, -'.json'.length);
    if (requested && !requested.has(id)) continue;
    try {
      records.push({ fileName: entry.name, record: JSON.parse(await readFile(join(problemsRoot, entry.name), 'utf8')) });
    } catch (error) {
      errors.push(`${entry.name}: invalid JSON (${error.message})`);
    }
  }
  if (requested) {
    const found = new Set(records.map(({ record }) => record?.problemId));
    for (const id of [...requested].sort()) {
      if (!found.has(id)) errors.push(`${id}: requested problem record was not found`);
    }
  }
  if (records.length === 0 && errors.length === 0) errors.push('no problem records were found');
  if (errors.length > 0) throw new Error(`Content loading failed:\n${errors.map((error) => `- ${error}`).join('\n')}`);
  return records;
}

function validateRecords(records, catalog, collectionId, setId) {
  const errors = [];
  const ids = new Map();
  const ordinals = new Map();
  for (const { fileName, record } of records) {
    for (const error of validateProblem(record, catalog)) errors.push(`${fileName}${error.slice(1)}`);
    if (record?.collectionId !== collectionId) errors.push(`${fileName}.collectionId: expected ${collectionId}`);
    if (record?.setId !== setId) errors.push(`${fileName}.setId: expected ${setId}`);
    if (typeof record?.problemId === 'string') addDuplicate(ids, record.problemId, fileName, 'problemId', errors);
    if (Number.isInteger(record?.ordinal)) addDuplicate(ordinals, record.ordinal, fileName, 'ordinal', errors);
  }
  return errors.sort((left, right) => left.localeCompare(right));
}

function addDuplicate(seen, value, fileName, field, errors) {
  if (seen.has(value)) errors.push(`${fileName}.${field}: duplicate ${field} ${value} also used by ${seen.get(value)}`);
  else seen.set(value, fileName);
}

async function copySharedAssets(stagingRoot, assets) {
  await writeAsset(stagingRoot, 'content/shared/content.css', await readFile(join(contentRoot, 'templates', 'content.css')), assets);
  await writeAsset(
    stagingRoot,
    'content/shared/katex/katex.min.css',
    await readFile(join(contentRoot, 'node_modules', 'katex', 'dist', 'katex.min.css')),
    assets,
  );
  const fontsRoot = join(contentRoot, 'node_modules', 'katex', 'dist', 'fonts');
  const fonts = (await readdir(fontsRoot, { withFileTypes: true }))
    .filter((entry) => entry.isFile())
    .sort((left, right) => left.name.localeCompare(right.name));
  for (const font of fonts) {
    await writeAsset(
      stagingRoot,
      `content/shared/katex/fonts/${font.name}`,
      await readFile(join(fontsRoot, font.name)),
      assets,
    );
  }
}

async function buildProblem(sourceRoot, stagingRoot, record, fileName, assets, membership) {
  const problemSourceRoot = join(sourceRoot, 'problems', record.problemId);
  const problemOutputRoot = `content/${record.collectionId}/${record.setId}/problems/${record.problemId}`;
  const title = `${membership.collection.title} ${membership.problemSet.title} ${record.label}`;
  const render = (document) => renderDocument(document, {
    sharedAssetPrefix: '../../../../shared',
    title,
  });

  const statement = await prepareDocument(record.statement, problemSourceRoot, problemOutputRoot, stagingRoot, assets);
  const statementAsset = await writeAsset(stagingRoot, `${problemOutputRoot}/statement.html`, render(statement), assets);
  const hints = [];
  for (const hint of [...record.hints].sort((left, right) => left.order - right.order)) {
    const document = await prepareDocument(hint.document, problemSourceRoot, problemOutputRoot, stagingRoot, assets);
    const asset = await writeAsset(stagingRoot, `${problemOutputRoot}/hint-${hint.order}.html`, render(document), assets);
    hints.push({ order: hint.order, stage: hint.stage, title: hint.title, ...assetReference(asset) });
  }
  const prerequisites = [];
  for (const prerequisite of [...record.prerequisites].sort((left, right) => left.order - right.order)) {
    const document = await prepareDocument(prerequisite.document, problemSourceRoot, problemOutputRoot, stagingRoot, assets);
    const asset = await writeAsset(
      stagingRoot,
      `${problemOutputRoot}/prerequisite-${prerequisite.order}.html`,
      render(document),
      assets,
    );
    prerequisites.push({ order: prerequisite.order, title: prerequisite.title, ...assetReference(asset) });
  }
  const solution = await prepareDocument(record.solution, problemSourceRoot, problemOutputRoot, stagingRoot, assets);
  const solutionAsset = await writeAsset(stagingRoot, `${problemOutputRoot}/solution.html`, render(solution), assets);

  return {
    key: problemKey(record.collectionId, record.setId, record.problemId),
    collectionId: record.collectionId,
    setId: record.setId,
    problemId: record.problemId,
    label: record.label,
    ordinal: record.ordinal,
    topics: record.topics,
    sourceRecord: fileName,
    statement: assetReference(statementAsset),
    hints,
    prerequisites,
    solution: assetReference(solutionAsset),
    sources: record.sources,
  };
}

async function prepareDocument(document, problemSourceRoot, problemOutputRoot, stagingRoot, assets) {
  const prepared = structuredClone(document);
  for (const block of prepared.blocks) {
    if (block.type !== 'figure') continue;
    const sourcePath = await resolveContainedFile(problemSourceRoot, block.path);
    const outputPath = `${problemOutputRoot}/assets/${block.path}`;
    await writeAsset(stagingRoot, outputPath, await readFile(sourcePath), assets);
    block.path = `assets/${block.path}`;
  }
  return prepared;
}

async function resolveContainedFile(root, localPath) {
  if (!isSafeLocalPath(localPath)) throw new Error(`Unsafe local asset path: ${localPath}`);
  const rootPath = resolve(root);
  const candidate = resolve(rootPath, ...localPath.split('/'));
  if (!isWithin(rootPath, candidate)) throw new Error(`Asset escapes its allowed directory: ${localPath}`);
  await access(candidate);
  const [realRoot, realCandidate] = await Promise.all([realpath(rootPath), realpath(candidate)]);
  if (!isWithin(realRoot, realCandidate)) throw new Error(`Asset symlink escapes its allowed directory: ${localPath}`);
  if (!(await stat(realCandidate)).isFile()) throw new Error(`Asset is not a file: ${localPath}`);
  return realCandidate;
}

async function writeAsset(stagingRoot, assetPath, content, assets) {
  const bytes = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
  const digest = sha256(bytes);
  const existing = assets.get(assetPath);
  if (existing) {
    if (existing.sha256 !== digest) throw new Error(`Generated asset collision at ${assetPath}`);
    return existing;
  }
  await writeGeneratedFile(stagingRoot, assetPath, bytes);
  const asset = { path: assetPath, sha256: digest, bytes: bytes.byteLength };
  assets.set(assetPath, asset);
  return asset;
}

async function writeGeneratedFile(stagingRoot, assetPath, content) {
  if (!isSafeLocalPath(assetPath)) throw new Error(`Unsafe generated asset path: ${assetPath}`);
  const destination = join(stagingRoot, ...assetPath.split('/'));
  await mkdir(dirname(destination), { recursive: true });
  await writeFile(destination, content);
}

function assetReference(asset) {
  return { assetPath: asset.path, sha256: asset.sha256 };
}

function compareProblems(left, right) {
  return left.ordinal - right.ordinal || left.problemId.localeCompare(right.problemId);
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
      Object.keys(value).sort((left, right) => left.localeCompare(right))
        .map((key) => [key, canonicalize(value[key])]),
    );
  }
  return value;
}

function isWithin(root, candidate) {
  const pathFromRoot = relative(root, candidate);
  return pathFromRoot === '' || (!pathFromRoot.startsWith(`..${sep}`) && pathFromRoot !== '..' && !isAbsolute(pathFromRoot));
}

function assertSeparateTrees(sourceRoot, outputRoot) {
  const filesystemRoot = resolve(outputRoot, sep);
  if (outputRoot === filesystemRoot || sourceRoot === outputRoot) {
    throw new Error('Source and output directories must be distinct, non-root paths');
  }
  if (isWithin(sourceRoot, outputRoot) || isWithin(outputRoot, sourceRoot)) {
    throw new Error('Source and output directories must not overlap');
  }
}

function parseArguments(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    const [name, inlineValue] = argument.split('=', 2);
    const takeValue = () => inlineValue ?? args[++index];
    if (name === '--source') options.source = takeValue();
    else if (name === '--asset-root') options.assetRoot = takeValue();
    else if (name === '--catalog') options.catalogPath = takeValue();
    else if (name === '--collection') options.collectionId = takeValue();
    else if (name === '--set') options.setId = takeValue();
    else if (name === '--content-version') options.contentVersion = takeValue();
    else if (name === '--problems') options.problems = takeValue().split(',').filter(Boolean);
    else if (name === '--database') options.database = takeValue();
    else throw new Error(`Unknown argument: ${argument}`);
  }
  for (const required of ['source', 'assetRoot', 'catalogPath', 'collectionId', 'setId', 'contentVersion']) {
    if (!options[required]) throw new Error(`--${required.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)} is required`);
  }
  return options;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const catalog = await loadLibraryCatalog(options.catalogPath);
  const manifest = await buildSet(options.source, options.assetRoot, { ...options, catalog });
  if (options.database) {
    const python = process.env.PUTNAM_PYTHON ?? 'python';
    const manifestPath = join(
      resolve(options.assetRoot),
      'content',
      options.collectionId,
      options.setId,
      'manifest.json',
    );
    await executeFile(python, [
      databaseBuilder,
      '--manifest', manifestPath,
      '--catalog', resolve(options.catalogPath),
      '--output', resolve(options.database),
    ]);
  }
  process.stdout.write(`Built ${manifest.problems.length} problem(s) for ${manifest.collectionId}/${manifest.setId}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();

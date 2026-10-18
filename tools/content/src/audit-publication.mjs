import { lstat, readFile, readdir } from 'node:fs/promises';
import { extname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { problemPackageDocuments } from './content-types.mjs';
import { verifyPackageArchive } from './package-archive.mjs';
import { validatePublicationCatalog } from './publication-catalog.mjs';
import { auditPackageAsset } from './safe-package-asset.mjs';
import { formatSchemaErrors, getProblemSchemaValidator } from './schema-registry.mjs';

const validateProblemSchema = getProblemSchemaValidator();
const FORBIDDEN_ARTIFACT = /(?:^|\/)(?:.*(?:original|review|source-lock|provenance).*)$|\.(?:pdf|tex|js|mjs|cjs|sh|ps1|bat|cmd)$/i;

export async function auditPublication(root, { requireClearedRights = false } = {}) {
  const errors = [];
  const rootPath = resolve(toPath(root));
  let files;
  try {
    files = await listFiles(rootPath, errors);
  } catch (error) {
    return [`$: cannot read publication root (${error.message})`];
  }
  for (const path of files) if (FORBIDDEN_ARTIFACT.test(path)) errors.push(`${path}: forbidden publication artifact`);

  const catalogRelativePath = 'content/v1/catalog.json';
  if (!files.includes(catalogRelativePath)) return uniqueSorted([...errors, `${catalogRelativePath}: missing catalog`]);
  let catalog;
  try {
    catalog = JSON.parse(await readFile(join(rootPath, ...catalogRelativePath.split('/')), 'utf8'));
  } catch (error) {
    return uniqueSorted([...errors, `${catalogRelativePath}: invalid JSON (${error.message})`]);
  }
  errors.push(...validatePublicationCatalog(catalog).map((error) => `${catalogRelativePath}${error.slice(1)}`));
  if (!Array.isArray(catalog.problems) || !Array.isArray(catalog.references)) return uniqueSorted(errors);

  const versionRoot = join(rootPath, 'content', 'v1');
  const referencedPackages = new Set();
  for (const [index, problem] of catalog.problems.entries()) {
    const path = `catalog.problems[${index}]`;
    if (requireClearedRights && problem?.rights?.redistributionStatus !== 'cleared') {
      errors.push(`${path}.rights.redistributionStatus: public publication requires cleared rights`);
    }
    const descriptor = problem?.package;
    if (!descriptor || typeof descriptor.path !== 'string') continue;
    referencedPackages.add(`content/v1/${descriptor.path}`);
    const expectedPath = `packages/problem/${problem.collectionId}/${problem.setId}/${problem.problemId}/${descriptor.sha256}.zip`;
    if (descriptor.path !== expectedPath) errors.push(`${path}.package.path: immutable filename/path must equal ${expectedPath}`);
    if (descriptor.minAppVersionCode !== catalog.minAppVersionCode) {
      errors.push(`${path}.package.minAppVersionCode: does not match catalog minimum`);
    }
    const archivePath = resolve(versionRoot, ...descriptor.path.split('/'));
    if (!isWithin(versionRoot, archivePath)) {
      errors.push(`${path}.package.path: escapes publication version root`);
      continue;
    }
    let archiveBytes;
    try { archiveBytes = await readFile(archivePath); } catch (error) {
      errors.push(`${path}.package.path: missing package (${error.message})`);
      continue;
    }
    let verified;
    try { verified = await verifyPackageArchive(archiveBytes, { expectedDescriptor: descriptor }); } catch (error) {
      errors.push(`${path}.package: ${error.message}`);
      continue;
    }
    if (verified.envelope.contentKey !== problem.key) errors.push(`${path}.key: package content key does not match catalog`);
    auditProblemMembers(problem, verified.members, path, errors);
  }
  for (const [index, reference] of catalog.references.entries()) {
    if (requireClearedRights && reference?.rights?.redistributionStatus !== 'cleared') {
      errors.push(`catalog.references[${index}].rights.redistributionStatus: public publication requires cleared rights`);
    }
    if (reference?.package?.path) referencedPackages.add(`content/v1/${reference.package.path}`);
  }

  for (const file of files) {
    if (file.endsWith('.zip') && !referencedPackages.has(file)) errors.push(`${file}: orphan package`);
    if (file !== catalogRelativePath && !file.endsWith('.zip') && !FORBIDDEN_ARTIFACT.test(file)) {
      errors.push(`${file}: unexpected publication file`);
    }
  }
  return uniqueSorted(errors);
}

function auditProblemMembers(catalogProblem, members, path, errors) {
  const sourceBytes = members.get('source/problem.json');
  if (!sourceBytes) {
    errors.push(`${path}: package is missing source/problem.json`);
    return;
  }
  let record;
  try {
    const source = new TextDecoder('utf-8', { fatal: true }).decode(sourceBytes);
    record = JSON.parse(source);
    if (source !== `${canonicalJson(record)}\n`) errors.push(`${path}: source/problem.json is not canonical JSON`);
  } catch (error) {
    errors.push(`${path}: invalid source/problem.json (${error.message})`);
    return;
  }
  if (!validateProblemSchema(record)) {
    errors.push(...formatSchemaErrors(validateProblemSchema.errors, `${path}.source`));
  }
  for (const field of ['collectionId', 'setId', 'problemId', 'label', 'ordinal']) {
    if (record[field] !== catalogProblem[field]) errors.push(`${path}.${field}: source record does not match catalog`);
  }
  if (canonicalJson(record.topics) !== canonicalJson(catalogProblem.topics)) errors.push(`${path}.topics: source record does not match catalog`);

  const expectedDocuments = new Set(problemPackageDocuments(record).map(({ path: memberPath }) => memberPath));
  const expectedAssets = new Set();
  for (const { document } of problemPackageDocuments(record)) {
    for (const block of document.blocks ?? []) if (block.type === 'figure') expectedAssets.add(`assets/${block.path}`);
  }
  const expected = new Set(['source/problem.json', ...expectedDocuments, ...expectedAssets]);
  for (const memberPath of members.keys()) if (!expected.has(memberPath)) errors.push(`${path}: undeclared logical member ${memberPath}`);
  for (const memberPath of expected) if (!members.has(memberPath)) errors.push(`${path}: missing logical member ${memberPath}`);

  for (const memberPath of expectedDocuments) {
    const bytes = members.get(memberPath);
    if (!bytes) continue;
    const fragment = new TextDecoder('utf-8', { fatal: false }).decode(bytes);
    if (/<\/?(?:html|head|body|script|style|base|frame|iframe|form|object|embed)\b/i.test(fragment)
        || /\son[a-z][a-z\d:_-]*\s*=/i.test(fragment)
        || /\s(?:src|href)="(?:https?:|data:|\/\/)/i.test(fragment)) {
      errors.push(`${path}: unsafe generated fragment ${memberPath}`);
    }
  }
  for (const memberPath of expectedAssets) {
    const bytes = members.get(memberPath);
    if (!bytes) continue;
    const mediaType = mediaTypeFor(memberPath);
    for (const error of auditPackageAsset({ path: memberPath, mediaType, bytes })) {
      errors.push(`${path}.${memberPath}${error.slice(1)}`);
    }
  }
}

function mediaTypeFor(path) {
  return ({
    '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg', '.webp': 'image/webp',
  })[extname(path).toLocaleLowerCase('en-US')];
}

async function listFiles(root, errors) {
  const files = [];
  async function visit(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const path = join(directory, entry.name);
      const item = await lstat(path);
      const local = relative(root, path).replaceAll('\\', '/');
      if (item.isSymbolicLink()) errors.push(`${local}: symbolic links are forbidden`);
      else if (item.isDirectory()) await visit(path);
      else if (item.isFile()) files.push(local);
      else errors.push(`${local}: special filesystem entries are forbidden`);
    }
  }
  await visit(root);
  return files;
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function isWithin(root, candidate) {
  const path = relative(root, candidate);
  return path === '' || (!path.startsWith(`..${sep}`) && path !== '..' && !isAbsolute(path));
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

function toPath(value) {
  return value instanceof URL ? fileURLToPath(value) : value;
}

async function main(argv) {
  let root;
  let requireClearedRights = false;
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === '--root') root = argv[++index];
    else if (argv[index] === '--require-cleared-rights') requireClearedRights = true;
    else throw new Error(`Unknown argument: ${argv[index]}`);
  }
  if (!root) throw new Error('Missing --root');
  const errors = await auditPublication(root, { requireClearedRights });
  if (errors.length) throw new Error(`Publication audit failed:\n${errors.map((error) => `- ${error}`).join('\n')}`);
  process.stdout.write('Publication audit passed\n');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.stack ?? error.message}\n`);
    process.exitCode = 1;
  });
}

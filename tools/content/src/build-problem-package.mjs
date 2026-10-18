import { access, readFile, realpath, stat } from 'node:fs/promises';
import { extname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  isSafeLocalPath,
  problemKey,
  problemPackageDocuments,
} from './content-types.mjs';
import { findCatalogSet } from './library-catalog.mjs';
import { createPackageArchive } from './package-archive.mjs';
import { renderDocumentFragment } from './render-document.mjs';
import { auditPackageAsset } from './safe-package-asset.mjs';
import { validateProblem } from './validate-problem.mjs';

const MEDIA_BY_EXTENSION = Object.freeze({
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg', '.webp': 'image/webp',
});

export async function buildProblemPackage({
  record,
  problemAssetRoot,
  libraryCatalog,
  minAppVersionCode,
}) {
  const recordErrors = validateProblem(record, libraryCatalog);
  if (recordErrors.length) throw new Error(`Problem validation failed:\n${formatErrors(recordErrors)}`);
  const membership = findCatalogSet(libraryCatalog, record.collectionId, record.setId);
  if (!membership) throw new Error(`Unknown catalog set: ${record.collectionId}/${record.setId}`);
  if (!Number.isSafeInteger(minAppVersionCode) || minAppVersionCode < 1) {
    throw new TypeError('minAppVersionCode must be a positive integer');
  }

  const contentKey = problemKey(record.collectionId, record.setId, record.problemId);
  const sourceRoot = toPath(problemAssetRoot);
  const assetMembers = new Map();
  const documentMembers = [];
  for (const item of problemPackageDocuments(record)) {
    const document = structuredClone(item.document);
    for (const block of document.blocks) {
      if (block.type !== 'figure') continue;
      const sourcePath = await resolveContainedAsset(sourceRoot, block.path);
      const packagePath = `assets/${block.path}`;
      const bytes = await readFile(sourcePath);
      const mediaType = MEDIA_BY_EXTENSION[extname(block.path).toLocaleLowerCase('en-US')];
      const errors = auditPackageAsset({ path: packagePath, mediaType, bytes });
      if (errors.length) throw new Error(`${block.path}: package asset audit failed:\n${formatErrors(errors)}`);
      const existing = assetMembers.get(packagePath);
      if (existing && !existing.bytes.equals(bytes)) throw new Error(`Conflicting package asset bytes: ${packagePath}`);
      assetMembers.set(packagePath, { path: packagePath, mediaType, bytes });
      block.path = packagePath;
    }
    documentMembers.push({
      path: item.path,
      mediaType: 'text/html; charset=utf-8',
      bytes: Buffer.from(`${renderDocumentFragment(document, { richDocumentVersion: 1 })}\n`, 'utf8'),
    });
  }

  const members = [
    {
      path: 'source/problem.json', mediaType: 'application/json',
      bytes: Buffer.from(JSON.stringify(record), 'utf8'),
    },
    ...documentMembers,
    ...assetMembers.values(),
  ];
  const { bytes, envelope } = await createPackageArchive({
    contentType: 'problem',
    contentKey,
    sourceSchemaVersion: record.schemaVersion,
    richDocumentVersion: 1,
    minAppVersionCode,
    members,
  });
  return {
    contentKey,
    archiveBytes: bytes,
    envelope,
    catalogMetadata: {
      key: contentKey,
      collectionId: record.collectionId,
      setId: record.setId,
      problemId: record.problemId,
      label: record.label,
      ordinal: record.ordinal,
      topics: structuredClone(record.topics),
    },
  };
}

async function resolveContainedAsset(root, localPath) {
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

function isWithin(root, candidate) {
  const path = relative(root, candidate);
  return path === '' || (!path.startsWith(`..${sep}`) && path !== '..' && !isAbsolute(path));
}

function toPath(value) {
  return value instanceof URL ? fileURLToPath(value) : value;
}

function formatErrors(errors) {
  return errors.map((error) => `- ${error}`).join('\n');
}

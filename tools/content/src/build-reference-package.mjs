import { access, readFile, realpath, stat } from 'node:fs/promises';
import { extname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { isSafeLocalPath, referenceKey } from './content-types.mjs';
import { createPackageArchive } from './package-archive.mjs';
import { renderReferenceArticle } from './render-reference.mjs';
import { auditPackageAsset } from './safe-package-asset.mjs';
import { validateReferenceArticle } from './validate-reference.mjs';

const MEDIA_BY_EXTENSION = Object.freeze({
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg', '.webp': 'image/webp',
});

export async function buildReferencePackage({
  record,
  articleAssetRoot,
  libraryCatalog,
  minAppVersionCode,
}) {
  const errors = validateReferenceArticle(record, { categories: libraryCatalog.referenceCategories });
  if (errors.length) throw new Error(`Reference validation failed:\n${formatErrors(errors)}`);
  if (!Number.isSafeInteger(minAppVersionCode) || minAppVersionCode < 1) {
    throw new TypeError('minAppVersionCode must be a positive integer');
  }

  const contentKey = referenceKey(record.categoryId, record.articleId);
  const renderedRecord = structuredClone(record);
  const assetMembers = new Map();
  for (const document of referenceDocuments(renderedRecord)) {
    await rewriteFigures(document, toPath(articleAssetRoot), assetMembers);
  }
  const members = [
    {
      path: 'source/reference.json', mediaType: 'application/json',
      bytes: Buffer.from(JSON.stringify(record), 'utf8'),
    },
    {
      path: 'documents/article.fragment.html', mediaType: 'text/html; charset=utf-8',
      bytes: Buffer.from(`${renderReferenceArticle(renderedRecord)}\n`, 'utf8'),
    },
    ...assetMembers.values(),
  ];
  const { bytes, envelope } = await createPackageArchive({
    contentType: 'reference',
    contentKey,
    sourceSchemaVersion: record.schemaVersion,
    richDocumentVersion: record.richDocumentVersion,
    minAppVersionCode,
    members,
  });
  return {
    contentKey,
    archiveBytes: bytes,
    envelope,
    catalogMetadata: {
      key: contentKey,
      categoryId: record.categoryId,
      articleId: record.articleId,
      title: record.title,
      displayOrder: record.displayOrder,
      aliases: structuredClone(record.aliases),
      keywords: structuredClone(record.keywords),
      rights: structuredClone(record.rights),
    },
  };
}

function referenceDocuments(record) {
  return [
    record.notation, record.definitions, record.results, record.conditions,
    ...record.examples.map(({ document }) => document), record.pitfalls,
  ];
}

async function rewriteFigures(document, root, assetMembers) {
  for (const block of document.blocks) {
    if (block.type === 'figure') {
      const originalPath = block.path;
      const sourcePath = await resolveContainedAsset(root, originalPath);
      const packagePath = `assets/${originalPath}`;
      const bytes = await readFile(sourcePath);
      const mediaType = MEDIA_BY_EXTENSION[extname(originalPath).toLocaleLowerCase('en-US')];
      const errors = auditPackageAsset({ path: packagePath, mediaType, bytes });
      if (errors.length) throw new Error(`${originalPath}: package asset audit failed:\n${formatErrors(errors)}`);
      const existing = assetMembers.get(packagePath);
      if (existing && !existing.bytes.equals(bytes)) throw new Error(`Conflicting package asset bytes: ${packagePath}`);
      assetMembers.set(packagePath, { path: packagePath, mediaType, bytes });
      block.path = packagePath;
    } else if (block.type === 'table') {
      for (const row of block.rows) for (const cell of row) await rewriteFigures(cell, root, assetMembers);
    }
  }
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

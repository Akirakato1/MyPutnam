import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildProblemPackage } from './build-problem-package.mjs';
import { buildReferencePackage } from './build-reference-package.mjs';
import { validateLibraryCatalog } from './library-catalog.mjs';
import { buildPublicationCatalog, validatePublicationManifest } from './publication-catalog.mjs';

export async function buildPublication({ sourceRoot, libraryCatalog, publicationManifest, outputRoot }) {
  const libraryErrors = validateLibraryCatalog(libraryCatalog);
  if (libraryErrors.length) throw new Error(`Invalid library catalog:\n${formatErrors(libraryErrors)}`);
  const manifestErrors = validatePublicationManifest(publicationManifest);
  if (manifestErrors.length) throw new Error(`Invalid publication manifest:\n${formatErrors(manifestErrors)}`);
  const sourcePath = resolve(toPath(sourceRoot));
  const outputPath = resolve(toPath(outputRoot));
  await assertEmptyOrAbsent(outputPath);
  await mkdir(dirname(outputPath), { recursive: true });
  const stagingRoot = await mkdtemp(join(dirname(outputPath), `.${basename(outputPath)}.tmp-`));
  try {
    const problemPackages = [];
    for (const selection of publicationManifest.includedSets) {
      const problemsRoot = join(sourcePath, selection.setId, 'problems');
      const entries = (await readdir(problemsRoot, { withFileTypes: true }))
        .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
        .sort((left, right) => left.name.localeCompare(right.name));
      for (const entry of entries) {
        const record = JSON.parse(await readFile(join(problemsRoot, entry.name), 'utf8'));
        if (record.collectionId !== selection.collectionId || record.setId !== selection.setId) {
          throw new Error(`${selection.collectionId}/${selection.setId}/${entry.name}: source identity does not match selection`);
        }
        const built = await buildProblemPackage({
          record,
          problemAssetRoot: join(problemsRoot, record.problemId),
          libraryCatalog,
          minAppVersionCode: publicationManifest.minAppVersionCode,
        });
        const archiveSha256 = sha256(built.archiveBytes);
        const descriptor = {
          contentType: 'problem',
          packageRevision: built.envelope.packageRevision,
          packageSchemaVersion: built.envelope.packageSchemaVersion,
          contentSchemaVersion: built.envelope.contentSchemaVersion,
          richDocumentVersion: built.envelope.richDocumentVersion,
          path: [
            'packages', 'problem', record.collectionId, record.setId, record.problemId,
            `${archiveSha256}.zip`,
          ].join('/'),
          compressedBytes: built.archiveBytes.byteLength,
          uncompressedBytes: built.envelope.declaredUncompressedBytes,
          sha256: archiveSha256,
          minAppVersionCode: built.envelope.minAppVersionCode,
        };
        await writeOutput(stagingRoot, join('content', 'v1', ...descriptor.path.split('/')), built.archiveBytes);
        problemPackages.push({ ...built, descriptor });
      }
    }

    const referencePackages = [];
    for (const categoryId of publicationManifest.includedReferenceCategories) {
      const articlesRoot = join(sourcePath, 'reference', 'articles', categoryId);
      const entries = (await readdir(articlesRoot, { withFileTypes: true }))
        .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
        .sort((left, right) => left.name.localeCompare(right.name));
      for (const entry of entries) {
        const record = JSON.parse(await readFile(join(articlesRoot, entry.name), 'utf8'));
        if (record.categoryId !== categoryId || entry.name !== `${record.articleId}.json`) {
          throw new Error(`${categoryId}/${entry.name}: source identity does not match selection`);
        }
        const built = await buildReferencePackage({
          record,
          articleAssetRoot: join(articlesRoot, record.articleId),
          libraryCatalog,
          minAppVersionCode: publicationManifest.minAppVersionCode,
        });
        const archiveSha256 = sha256(built.archiveBytes);
        const descriptor = {
          contentType: 'reference',
          packageRevision: built.envelope.packageRevision,
          packageSchemaVersion: built.envelope.packageSchemaVersion,
          contentSchemaVersion: built.envelope.contentSchemaVersion,
          richDocumentVersion: built.envelope.richDocumentVersion,
          path: ['packages', 'reference', categoryId, record.articleId, `${archiveSha256}.zip`].join('/'),
          compressedBytes: built.archiveBytes.byteLength,
          uncompressedBytes: built.envelope.declaredUncompressedBytes,
          sha256: archiveSha256,
          minAppVersionCode: built.envelope.minAppVersionCode,
        };
        await writeOutput(stagingRoot, join('content', 'v1', ...descriptor.path.split('/')), built.archiveBytes);
        referencePackages.push({ ...built, descriptor });
      }
    }

    const catalog = buildPublicationCatalog({
      publicationManifest,
      libraryCatalog,
      problemPackages,
      referencePackages,
    });
    const catalogRelativePath = join('content', 'v1', 'catalog.json');
    await writeOutput(stagingRoot, catalogRelativePath, `${JSON.stringify(catalog, null, 2)}\n`);
    await rm(outputPath, { recursive: true, force: true });
    await rename(stagingRoot, outputPath);
    return {
      catalogPath: join(outputPath, catalogRelativePath),
      problemCount: catalog.problems.length,
      referenceCount: catalog.references.length,
      packageCount: problemPackages.length + referencePackages.length,
    };
  } catch (error) {
    await rm(stagingRoot, { recursive: true, force: true });
    throw error;
  }
}

async function assertEmptyOrAbsent(path) {
  try {
    const entries = await readdir(path);
    if (entries.length > 0) throw new Error(`Publication output root must be empty: ${path}`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
}

async function writeOutput(root, relativePath, value) {
  const path = join(root, relativePath);
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, value);
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
  const [libraryCatalog, publicationManifest] = await Promise.all([
    readJson(args.library), readJson(args.publication),
  ]);
  const result = await buildPublication({
    sourceRoot: args.source,
    libraryCatalog,
    publicationManifest,
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
  for (const name of ['source', 'library', 'publication', 'output']) if (!args[name]) throw new Error(`Missing --${name}`);
  return args;
}

async function readJson(path) {
  return JSON.parse(await readFile(path, 'utf8'));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.stack ?? error.message}\n`);
    process.exitCode = 1;
  });
}

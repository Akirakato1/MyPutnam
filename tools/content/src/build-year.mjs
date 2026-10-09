import { createHash } from 'node:crypto';
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

import { isSafeLocalPath, SCHEMA_VERSION } from './content-types.mjs';
import { renderDocument } from './render-document.mjs';
import { renderReview } from './render-review.mjs';
import { validateProblem } from './validate-problem.mjs';
import { buildSet } from './build-set.mjs';
import { loadLibraryCatalog } from './library-catalog.mjs';

const contentRoot = fileURLToPath(new URL('..', import.meta.url));
const repositoryRoot = fileURLToPath(new URL('../../..', import.meta.url));

export async function buildYear(sourceDir, outputDir, options = {}) {
  const year = options.year ?? await inferYear(sourceDir);
  const catalog = options.catalog ?? await loadLibraryCatalog(
    options.catalogPath ?? join(repositoryRoot, 'content-src', 'library.json'),
  );
  return buildSet(sourceDir, outputDir, {
    catalog,
    collectionId: 'putnam',
    setId: String(year),
    contentVersion: options.contentVersion ?? `putnam-${year}.1`,
    ...(options.problems ? { problems: options.problems } : {}),
  });
}

async function inferYear(sourceDir) {
  const records = await loadRecords(resolve(sourceDir));
  const setId = records[0]?.record?.setId;
  if (!/^\d{4}$/.test(setId ?? '')) throw new Error('Cannot infer Putnam year from setId');
  return Number.parseInt(setId, 10);
}

async function loadRecords(sourceRoot, requestedProblems) {
  const problemsRoot = join(sourceRoot, 'problems');
  const requested = requestedProblems ? new Set(requestedProblems) : null;
  const entries = (await readdir(problemsRoot, { withFileTypes: true }))
    .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
    .sort((left, right) => left.name.localeCompare(right.name));

  const records = [];
  const parseErrors = [];
  for (const entry of entries) {
    const code = entry.name.slice(0, -'.json'.length);
    if (requested && !requested.has(code)) {
      continue;
    }

    try {
      records.push({
        fileName: entry.name,
        record: JSON.parse(await readFile(join(problemsRoot, entry.name), 'utf8')),
      });
    } catch (error) {
      parseErrors.push(`${entry.name}: invalid JSON (${error.message})`);
    }
  }

  if (requested) {
    const found = new Set(records.map(({ record }) => record?.code));
    for (const code of [...requested].sort()) {
      if (!found.has(code)) {
        parseErrors.push(`${code}: requested problem record was not found`);
      }
    }
  }

  if (records.length === 0 && parseErrors.length === 0) {
    parseErrors.push('no problem records were found');
  }

  if (parseErrors.length > 0) {
    throw new Error(`Content loading failed:\n${parseErrors.map((error) => `- ${error}`).join('\n')}`);
  }
  return records;
}

function validateRecords(records, expectedYear) {
  const errors = [];
  const codes = new Map();
  const ordinals = new Map();

  for (const { fileName, record } of records) {
    for (const error of validateProblem(record)) {
      errors.push(`${fileName}${error.slice(1)}`);
    }

    if (expectedYear !== undefined && record?.year !== expectedYear) {
      errors.push(`${fileName}.year: expected ${expectedYear}`);
    }

    if (typeof record?.code === 'string') {
      if (codes.has(record.code)) {
        errors.push(`${fileName}.code: duplicate code ${record.code} also used by ${codes.get(record.code)}`);
      } else {
        codes.set(record.code, fileName);
      }
    }

    if (Number.isInteger(record?.ordinal)) {
      if (ordinals.has(record.ordinal)) {
        errors.push(`${fileName}.ordinal: duplicate ordinal ${record.ordinal} also used by ${ordinals.get(record.ordinal)}`);
      } else {
        ordinals.set(record.ordinal, fileName);
      }
    }
  }

  const years = new Set(records.map(({ record }) => record?.year));
  if (years.size > 1) {
    errors.push(`records contain multiple years: ${[...years].sort().join(', ')}`);
  }

  return errors.sort((left, right) => left.localeCompare(right));
}

async function copySharedAssets(stagingRoot, assets) {
  await writeAsset(
    stagingRoot,
    'shared/content.css',
    await readFile(join(contentRoot, 'templates', 'content.css')),
    assets,
  );
  await writeAsset(
    stagingRoot,
    'shared/katex/katex.min.css',
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
      `shared/katex/fonts/${font.name}`,
      await readFile(join(fontsRoot, font.name)),
      assets,
    );
  }
}

async function buildProblem(sourceRoot, stagingRoot, record, fileName, assets) {
  const problemSourceRoot = join(sourceRoot, 'problems', record.code);
  const problemOutputRoot = `problems/${record.code}`;

  const statement = await prepareDocument(
    record.statement,
    problemSourceRoot,
    problemOutputRoot,
    stagingRoot,
    assets,
  );
  const statementAsset = await writeAsset(
    stagingRoot,
    `${problemOutputRoot}/statement.html`,
    renderDocument(statement),
    assets,
  );

  const hints = [];
  for (const hint of [...record.hints].sort((left, right) => left.order - right.order)) {
    const document = await prepareDocument(
      hint.document,
      problemSourceRoot,
      problemOutputRoot,
      stagingRoot,
      assets,
    );
    const asset = await writeAsset(
      stagingRoot,
      `${problemOutputRoot}/hint-${hint.order}.html`,
      renderDocument(document),
      assets,
    );
    hints.push({
      order: hint.order,
      stage: hint.stage,
      title: hint.title,
      ...assetReference(asset),
    });
  }

  const prerequisites = [];
  for (const prerequisite of [...record.prerequisites].sort((left, right) => left.order - right.order)) {
    const document = await prepareDocument(
      prerequisite.document,
      problemSourceRoot,
      problemOutputRoot,
      stagingRoot,
      assets,
    );
    const asset = await writeAsset(
      stagingRoot,
      `${problemOutputRoot}/prerequisite-${prerequisite.order}.html`,
      renderDocument(document),
      assets,
    );
    prerequisites.push({
      order: prerequisite.order,
      title: prerequisite.title,
      ...assetReference(asset),
    });
  }

  const solution = await prepareDocument(
    record.solution,
    problemSourceRoot,
    problemOutputRoot,
    stagingRoot,
    assets,
  );
  const solutionAsset = await writeAsset(
    stagingRoot,
    `${problemOutputRoot}/solution.html`,
    renderDocument(solution),
    assets,
  );

  return {
    year: record.year,
    code: record.code,
    ordinal: record.ordinal,
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
    if (block.type !== 'figure') {
      continue;
    }

    const sourcePath = await resolveContainedFile(problemSourceRoot, block.path);
    const outputPath = `${problemOutputRoot}/assets/${block.path}`;
    await writeAsset(stagingRoot, outputPath, await readFile(sourcePath), assets);
    block.path = `assets/${block.path}`;
  }
  return prepared;
}

async function resolveContainedFile(root, localPath) {
  if (!isSafeLocalPath(localPath)) {
    throw new Error(`Unsafe local asset path: ${localPath}`);
  }

  const rootPath = resolve(root);
  const candidate = resolve(rootPath, ...localPath.split('/'));
  if (!isWithin(rootPath, candidate)) {
    throw new Error(`Asset escapes its allowed directory: ${localPath}`);
  }

  await access(candidate);
  const [realRoot, realCandidate] = await Promise.all([realpath(rootPath), realpath(candidate)]);
  if (!isWithin(realRoot, realCandidate)) {
    throw new Error(`Asset symlink escapes its allowed directory: ${localPath}`);
  }
  if (!(await stat(realCandidate)).isFile()) {
    throw new Error(`Asset is not a file: ${localPath}`);
  }
  return realCandidate;
}

async function writeAsset(stagingRoot, assetPath, content, assets) {
  if (!isSafeLocalPath(assetPath)) {
    throw new Error(`Unsafe generated asset path: ${assetPath}`);
  }

  const bytes = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const existing = assets.get(assetPath);
  if (existing) {
    if (existing.sha256 !== sha256) {
      throw new Error(`Generated asset collision at ${assetPath}`);
    }
    return existing;
  }

  const destination = join(stagingRoot, ...assetPath.split('/'));
  await mkdir(dirname(destination), { recursive: true });
  await writeFile(destination, bytes);
  const asset = { path: assetPath, sha256, bytes: bytes.byteLength };
  assets.set(assetPath, asset);
  return asset;
}

function assetReference(asset) {
  return { assetPath: asset.path, sha256: asset.sha256 };
}

function compareProblems(left, right) {
  return left.ordinal - right.ordinal || left.code.localeCompare(right.code);
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
  const options = { review: true };
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    const [name, inlineValue] = argument.split('=', 2);
    const takeValue = () => inlineValue ?? args[++index];
    switch (name) {
      case '--year':
        options.year = Number.parseInt(takeValue(), 10);
        break;
      case '--content-version':
        options.contentVersion = takeValue();
        break;
      case '--problems':
        options.problems = takeValue().split(',').filter(Boolean);
        break;
      case '--source':
        options.source = takeValue();
        break;
      case '--catalog':
        options.catalogPath = takeValue();
        break;
      case '--output':
        options.output = takeValue();
        break;
      case '--review-output':
        options.reviewOutput = takeValue();
        break;
      case '--no-review':
        options.review = false;
        break;
      default:
        throw new Error(`Unknown argument: ${argument}`);
    }
  }
  if (!Number.isInteger(options.year)) {
    throw new Error('--year is required and must be an integer');
  }
  if (options.contentVersion !== undefined && !options.contentVersion.trim()) {
    throw new Error('--content-version must not be empty');
  }
  return options;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const sourceDir = options.source ?? join(repositoryRoot, 'content-src', String(options.year));
  const outputDir = options.output
    ?? join(repositoryRoot, 'build', 'content-preview-assets', String(options.year));
  const manifest = await buildYear(sourceDir, outputDir, options);

  if (options.review) {
    const reviewOutput = options.reviewOutput
      ?? join(repositoryRoot, 'build', 'content-review', String(options.year));
    await renderReview(outputDir, reviewOutput);
  }

  process.stdout.write(
    `Built ${manifest.problems.length} problem(s) for ${manifest.collectionId}/${manifest.setId} at ${outputDir}\n`,
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}

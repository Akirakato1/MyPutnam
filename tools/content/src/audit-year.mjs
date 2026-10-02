import { readFile, readdir, stat } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { auditSourceLocks } from './audit-source-locks.mjs';
import {
  documentFigurePaths,
  isPlainObject,
  isSafeLocalPath,
  problemDocuments,
} from './content-types.mjs';
import { validateProblem } from './validate-problem.mjs';

const repositoryRoot = fileURLToPath(new URL('../../..', import.meta.url));

export const CANONICAL_PROBLEMS = Object.freeze([
  { code: 'A1', ordinal: 1 },
  { code: 'A2', ordinal: 2 },
  { code: 'A3', ordinal: 3 },
  { code: 'A4', ordinal: 4 },
  { code: 'A5', ordinal: 5 },
  { code: 'A6', ordinal: 6 },
  { code: 'B1', ordinal: 7 },
  { code: 'B2', ordinal: 8 },
  { code: 'B3', ordinal: 9 },
  { code: 'B4', ordinal: 10 },
  { code: 'B5', ordinal: 11 },
  { code: 'B6', ordinal: 12 },
]);

export async function auditYear(sourceRoot, expectedYear) {
  const root = resolve(sourceRoot instanceof URL ? fileURLToPath(sourceRoot) : sourceRoot);
  const errors = [...await auditSourceLocks(root)];
  const sourceManifest = await readSourceManifest(root);
  const sourceUrls = new Set(
    Array.isArray(sourceManifest?.sources)
      ? sourceManifest.sources.map(({ url }) => url).filter((url) => typeof url === 'string')
      : [],
  );
  const sourceFiles = new Set(
    Array.isArray(sourceManifest?.sources)
      ? sourceManifest.sources.map(({ file }) => file).filter((file) => typeof file === 'string')
      : [],
  );

  if (sourceManifest && sourceManifest.year !== expectedYear) {
    errors.push('sources.json.year: expected ' + expectedYear);
  }

  const problemsRoot = join(root, 'problems');
  let problemEntries = [];
  try {
    problemEntries = await readdir(problemsRoot, { withFileTypes: true });
  } catch (error) {
    errors.push(
      error.code === 'ENOENT'
        ? 'problems/: missing problem directory'
        : 'problems/: cannot read directory (' + error.message + ')',
    );
  }

  const jsonFiles = problemEntries
    .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
    .map((entry) => entry.name)
    .sort((left, right) => left.localeCompare(right));
  const expectedFiles = new Set(CANONICAL_PROBLEMS.map(({ code }) => code + '.json'));

  for (const name of jsonFiles) {
    if (!expectedFiles.has(name)) {
      errors.push('problems/' + name + ': unexpected JSON problem record');
    }
  }

  for (const expected of CANONICAL_PROBLEMS) {
    const fileName = expected.code + '.json';
    const recordPath = 'problems/' + fileName;
    if (!jsonFiles.includes(fileName)) {
      errors.push(recordPath + ': missing required problem record');
      continue;
    }

    let record;
    try {
      record = JSON.parse(await readFile(join(problemsRoot, fileName), 'utf8'));
    } catch (error) {
      errors.push(recordPath + ': invalid JSON (' + error.message + ')');
      continue;
    }

    for (const error of validateProblem(record)) {
      errors.push(recordPath + error.slice(1));
    }
    if (record?.year !== expectedYear) {
      errors.push(recordPath + '.year: expected ' + expectedYear);
    }
    if (record?.code !== expected.code) {
      errors.push(recordPath + '.code: expected ' + expected.code);
    }
    if (record?.ordinal !== expected.ordinal) {
      errors.push(recordPath + '.ordinal: expected ' + expected.ordinal);
    }
    if (!Array.isArray(record?.hints) || record.hints.length !== 4) {
      errors.push(recordPath + '.hints: expected exactly four staged hints');
    }
    if (!Array.isArray(record?.prerequisites) || record.prerequisites.length === 0) {
      errors.push(recordPath + '.prerequisites: expected at least one prerequisite');
    }
    if (!Array.isArray(record?.solution?.blocks) || record.solution.blocks.length < 3) {
      errors.push(recordPath + '.solution: expected at least three blocks for a complete explanation');
    }

    validateSourceCitations(record, recordPath, sourceUrls, errors);
    const figures = collectFigurePaths(record);
    await validateFigureFiles(root, expected.code, figures, recordPath, errors);
    await validateReviewNote(root, expectedYear, expected.code, sourceFiles, figures, errors);
  }

  return uniqueSorted(errors);
}

function validateSourceCitations(record, recordPath, sourceUrls, errors) {
  const citations = Array.isArray(record?.sources) ? record.sources : [];
  for (const kind of ['problem', 'solution']) {
    const matches = citations.filter((source) => source?.kind === kind);
    if (matches.length === 0) {
      errors.push(recordPath + '.sources: missing required ' + kind + ' citation');
      continue;
    }
    for (const source of matches) {
      if (typeof source.url === 'string' && !sourceUrls.has(source.url)) {
        errors.push(recordPath + '.sources: ' + kind + ' citation URL is absent from sources.json');
      }
    }
  }
}

function collectFigurePaths(record) {
  const figures = [];
  for (const { document } of problemDocuments(record)) {
    figures.push(...documentFigurePaths(document));
  }
  return [...new Set(figures)].sort((left, right) => left.localeCompare(right));
}

async function validateFigureFiles(root, code, figures, recordPath, errors) {
  for (const figure of figures) {
    if (!isSafeLocalPath(figure)) {
      continue;
    }
    const figurePath = join(root, 'problems', code, ...figure.split('/'));
    try {
      if (!(await stat(figurePath)).isFile()) {
        errors.push(recordPath + ': local figure is not a file: ' + figure);
      }
    } catch (error) {
      errors.push(
        error.code === 'ENOENT'
          ? recordPath + ': missing local figure ' + figure
          : recordPath + ': cannot inspect local figure ' + figure + ' (' + error.message + ')',
      );
    }
  }
}

async function validateReviewNote(root, year, code, sourceFiles, figures, errors) {
  const reviewPath = 'review/' + code + '.md';
  let review;
  try {
    review = await readFile(join(root, 'review', code + '.md'), 'utf8');
  } catch (error) {
    errors.push(
      error.code === 'ENOENT'
        ? reviewPath + ': missing review note'
        : reviewPath + ': cannot read review note (' + error.message + ')',
    );
    return;
  }

  const heading = new RegExp('^#\\s+' + year + '\\s+Putnam\\s+' + code + '\\s+content review$', 'mi');
  if (!heading.test(review)) {
    errors.push(reviewPath + ': missing canonical review heading');
  }
  for (const label of ['Statement fidelity', 'Mathematical review', 'Render review']) {
    const pattern = new RegExp('^-\\s*' + escapeRegExp(label) + ':\\s*complete\\b', 'mi');
    if (!pattern.test(review)) {
      errors.push(reviewPath + ': ' + label + ' must be marked complete');
    }
  }
  if (!/^##\s+Hint separation\s*$/mi.test(review)) {
    errors.push(reviewPath + ': missing Hint separation review');
  }

  const tick = String.fromCharCode(96);
  for (const label of ['Problem', 'Solution']) {
    const pattern = new RegExp(
      '^-\\s*' + label + ' source:\\s*' + tick + '([^' + tick + ']*)' + tick,
      'mi',
    );
    const match = review.match(pattern);
    if (!match) {
      errors.push(reviewPath + ': missing ' + label.toLowerCase() + ' source evidence');
    } else if (!sourceFiles.has(match[1])) {
      errors.push(reviewPath + ': ' + label.toLowerCase() + ' source ' + match[1] + ' is absent from sources.json');
    }
  }

  for (const figure of figures) {
    const pattern = new RegExp(
      '^-\\s*Figure review:\\s*' + tick + escapeRegExp(figure) + tick + '.*\\bcomplete\\b',
      'mi',
    );
    if (!pattern.test(review)) {
      errors.push(reviewPath + ': Figure review for ' + figure + ' must be marked complete');
    }
  }
}

async function readSourceManifest(root) {
  try {
    const value = JSON.parse(await readFile(join(root, 'sources.json'), 'utf8'));
    return isPlainObject(value) ? value : null;
  } catch {
    return null;
  }
}

function parseArguments(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    const split = argument.indexOf('=');
    const name = split === -1 ? argument : argument.slice(0, split);
    const inlineValue = split === -1 ? undefined : argument.slice(split + 1);
    const takeValue = () => inlineValue ?? args[++index];
    if (name === '--year') {
      options.year = Number.parseInt(takeValue(), 10);
    } else if (name === '--source') {
      options.source = takeValue();
    } else {
      throw new Error('Unknown argument: ' + argument);
    }
  }
  if (!Number.isInteger(options.year)) {
    throw new Error('--year is required and must be an integer');
  }
  return options;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const sourceRoot = options.source ?? join(repositoryRoot, 'content-src', String(options.year));
  const errors = await auditYear(sourceRoot, options.year);
  if (errors.length > 0) {
    throw new Error(
      'Year ' + options.year + ' audit failed:\n' + errors.map((error) => '- ' + error).join('\n'),
    );
  }
  process.stdout.write('Year ' + options.year + ' audit passed\n');
}

function escapeRegExp(value) {
  const special = '\\^$.*+?()[]{}|';
  return [...value].map((character) => special.includes(character) ? '\\' + character : character).join('');
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}

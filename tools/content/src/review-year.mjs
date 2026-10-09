import { join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { auditYear } from './audit-year.mjs';
import { buildYear } from './build-year.mjs';
import { renderReview } from './render-review.mjs';

const repositoryRoot = fileURLToPath(new URL('../../..', import.meta.url));
const appRoot = join(repositoryRoot, 'app');

export async function reviewYear({
  year,
  sourceRoot,
  previewRoot,
  reviewRoot,
  catalog,
  catalogPath,
  problems,
}) {
  if (!Number.isInteger(year)) {
    throw new Error('year must be an integer');
  }
  if (!sourceRoot || !previewRoot || !reviewRoot) {
    throw new Error('sourceRoot, previewRoot, and reviewRoot are required');
  }

  const resolvedSource = resolve(sourceRoot);
  const resolvedPreview = resolve(previewRoot);
  const resolvedReview = resolve(reviewRoot);
  assertReviewRoots(resolvedSource, resolvedPreview, resolvedReview);

  const errors = await auditYear(resolvedSource, year, { catalog, catalogPath });
  if (errors.length > 0) {
    throw new Error(
      'Year ' + year + ' audit failed:\n' + errors.map((error) => '- ' + error).join('\n'),
    );
  }

  const manifest = await buildYear(resolvedSource, resolvedPreview, {
    year,
    contentVersion: String(year) + '.review',
    catalog,
    catalogPath,
    ...(problems ? { problems } : {}),
  });
  const review = await renderReview(resolvedPreview, resolvedReview);
  return { manifest, review };
}

function assertReviewRoots(sourceRoot, previewRoot, reviewRoot) {
  for (const [label, directory] of [
    ['preview', previewRoot],
    ['review', reviewRoot],
  ]) {
    if (isWithin(appRoot, directory)) {
      throw new Error('Refusing to write ' + label + ' output beneath app: ' + directory);
    }
  }

  if (sourceRoot === previewRoot || sourceRoot === reviewRoot
      || isWithin(sourceRoot, previewRoot) || isWithin(previewRoot, sourceRoot)
      || isWithin(sourceRoot, reviewRoot) || isWithin(reviewRoot, sourceRoot)
      || isWithin(previewRoot, reviewRoot) || isWithin(reviewRoot, previewRoot)) {
    throw new Error('Source, preview, and review roots must be separate, non-overlapping directories');
  }
}

function isWithin(root, candidate) {
  const pathFromRoot = relative(resolve(root), resolve(candidate));
  return pathFromRoot === ''
    || (!pathFromRoot.startsWith('..' + sep) && pathFromRoot !== '..');
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
      options.sourceRoot = takeValue();
    } else if (name === '--preview-output') {
      options.previewRoot = takeValue();
    } else if (name === '--review-output') {
      options.reviewRoot = takeValue();
    } else if (name === '--problems') {
      options.problems = takeValue().split(',').filter(Boolean);
    } else if (name === '--catalog') {
      options.catalogPath = takeValue();
    } else {
      throw new Error('Unknown argument: ' + argument);
    }
  }

  if (!Number.isInteger(options.year)) {
    throw new Error('--year is required and must be an integer');
  }
  options.sourceRoot ??= join(repositoryRoot, 'content-src', String(options.year));
  options.previewRoot ??= join(repositoryRoot, 'build', 'content-preview', String(options.year));
  options.reviewRoot ??= join(repositoryRoot, 'build', 'content-review', String(options.year));
  return options;
}

async function main() {
  const result = await reviewYear(parseArguments(process.argv.slice(2)));
  process.stdout.write(
    'Reviewed ' + result.manifest.problems.length + ' problem(s) for '
      + result.manifest.collectionId + '/' + result.manifest.setId + '\n',
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}

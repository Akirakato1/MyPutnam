import { readFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';

import Ajv2020 from 'ajv/dist/2020.js';

const schema = JSON.parse(
  readFileSync(new URL('../schema/archive-catalog.schema.json', import.meta.url), 'utf8'),
);
const ajv = new Ajv2020({ allErrors: true, strict: true });
const validateSchema = ajv.compile(schema);

export function validateArchiveCatalog(value) {
  const errors = [];

  if (!validateSchema(value)) {
    for (const error of validateSchema.errors ?? []) {
      errors.push(`${schemaErrorPath(error)}: ${error.message}`);
    }
  }

  if (!isPlainObject(value)) {
    return uniqueSorted(errors);
  }

  validateStrictlyAscending(value.targetYears, '$.targetYears', errors);
  validateStrictlyAscending(value.completedYears, '$.completedYears', errors);

  if (Array.isArray(value.targetYears) && Array.isArray(value.completedYears)) {
    const targets = new Set(value.targetYears);
    for (const [index, year] of value.completedYears.entries()) {
      if (Number.isInteger(year) && !targets.has(year)) {
        errors.push(`$.completedYears[${index}]: year ${year} is not present in targetYears`);
      }
    }
  }

  return uniqueSorted(errors);
}

export async function loadArchiveCatalog(path) {
  const catalog = JSON.parse(await readFile(path, 'utf8'));
  const errors = validateArchiveCatalog(catalog);
  if (errors.length > 0) {
    throw new Error(`Invalid archive catalog:\n${errors.join('\n')}`);
  }
  return catalog;
}

export function assertFinalArchiveComplete(catalog) {
  const errors = validateArchiveCatalog(catalog);
  if (errors.length > 0) {
    throw new Error(`Invalid archive catalog:\n${errors.join('\n')}`);
  }

  const completed = new Set(catalog.completedYears);
  const missing = catalog.targetYears.filter((year) => !completed.has(year));
  if (missing.length > 0 || catalog.completedYears.length !== catalog.targetYears.length) {
    throw new Error(
      `Archive catalog incomplete: ${missing.length} target years are not completed` +
        (missing.length > 0 ? ` (${missing.join(', ')})` : ''),
    );
  }
}

export function assertReleaseComplete(release, catalog) {
  const errors = validateArchiveCatalog(catalog);
  if (errors.length > 0) {
    throw new Error(`Invalid archive catalog:\n${errors.join('\n')}`);
  }
  if (!release || !Array.isArray(release.includedSets)) {
    throw new Error('Invalid release manifest: includedSets must be an array');
  }

  const completed = new Set(catalog.completedYears);
  for (const key of release.includedSets) {
    if (typeof key !== 'string') continue;
    const [collectionId, setId, ...extra] = key.split('/');
    if (extra.length > 0 || collectionId !== 'putnam' || !/^\d{4}$/.test(setId ?? '')) continue;
    if (!completed.has(Number(setId))) {
      throw new Error(`Release set ${key} is not complete in the archive catalog`);
    }
  }
}

function validateStrictlyAscending(years, path, errors) {
  if (!Array.isArray(years)) {
    return;
  }

  years.forEach((year, index) => {
    if (index > 0 && Number.isInteger(year) && Number.isInteger(years[index - 1])) {
      if (year <= years[index - 1]) {
        errors.push(`${path}[${index}]: years must be unique and strictly ascending`);
      }
    }
  });
}

function schemaErrorPath(error) {
  let path = '$';
  const segments = error.instancePath.split('/').slice(1).map(unescapeJsonPointer);
  for (const segment of segments) {
    path += /^\\d+$/.test(segment) ? `[${segment}]` : `.${segment}`;
  }

  if (error.keyword === 'required') {
    path += `.${error.params.missingProperty}`;
  }
  if (error.keyword === 'additionalProperties') {
    path += `.${error.params.additionalProperty}`;
  }

  return path;
}

function unescapeJsonPointer(value) {
  return value.replaceAll('~1', '/').replaceAll('~0', '~');
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}


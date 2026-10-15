import { readFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';

import Ajv2020 from 'ajv/dist/2020.js';

import {
  assertReleaseComplete as assertArchiveReleaseComplete,
  validateArchiveCatalog,
} from './archive-catalog.mjs';
import { findCatalogSet, validateLibraryCatalog } from './library-catalog.mjs';

const schema = JSON.parse(
  readFileSync(new URL('../schema/release-manifest.schema.json', import.meta.url), 'utf8'),
);
const validateSchema = new Ajv2020({ allErrors: true, strict: true }).compile(schema);

export function validateReleaseManifest(value, archiveCatalog, libraryCatalog) {
  const errors = [];

  if (!validateSchema(value)) {
    for (const error of validateSchema.errors ?? []) {
      errors.push(`${schemaErrorPath(error)}: ${error.message}`);
    }
  }

  const archiveErrors = validateArchiveCatalog(archiveCatalog);
  errors.push(...archiveErrors.map((error) => `archive catalog ${error}`));
  const libraryErrors = validateLibraryCatalog(libraryCatalog);
  errors.push(...libraryErrors.map((error) => `library catalog ${error}`));

  if (!isPlainObject(value) || !Array.isArray(value.includedSets)) {
    return uniqueSorted(errors);
  }

  const seen = new Set();
  for (const [index, key] of value.includedSets.entries()) {
    const path = `$.includedSets[${index}]`;
    if (typeof key !== 'string') continue;

    if (seen.has(key)) errors.push(`${path}: duplicate included set ${key}`);
    seen.add(key);
    if (index > 0 && typeof value.includedSets[index - 1] === 'string'
        && key <= value.includedSets[index - 1]) {
      errors.push(`${path}: included sets must be unique and strictly ascending`);
    }

    const segments = key.split('/');
    if (segments.length !== 2 || segments.some((segment) => !isSafeId(segment))) continue;
    const [collectionId, setId] = segments;
    if (!findCatalogSet(libraryCatalog, collectionId, setId)) {
      errors.push(`${path}: set ${key} is not present in the library catalog`);
    }

    if (collectionId === 'putnam' && /^\d{4}$/.test(setId)
        && !archiveCatalog?.completedYears?.includes(Number(setId))) {
      errors.push(`${path}: ${key} is not complete in the archive catalog`);
    }
  }

  if (value.contentSchemaVersion !== archiveCatalog?.contentSchemaVersion) {
    errors.push('$.contentSchemaVersion: does not match the archive catalog');
  }
  if (value.contentSchemaVersion !== libraryCatalog?.contentSchemaVersion) {
    errors.push('$.contentSchemaVersion: does not match the library catalog');
  }

  return uniqueSorted(errors);
}

export async function loadReleaseManifest(path, archiveCatalog, libraryCatalog) {
  let value;
  try {
    value = JSON.parse(await readFile(path, 'utf8'));
  } catch (error) {
    throw new Error(`Cannot load release manifest ${path}: ${error.message}`, { cause: error });
  }

  const errors = validateReleaseManifest(value, archiveCatalog, libraryCatalog);
  if (errors.length > 0) {
    throw new Error(`Release manifest validation failed:\n${errors.map((error) => `- ${error}`).join('\n')}`);
  }
  return value;
}

export function assertReleaseComplete(release, archiveCatalog) {
  assertArchiveReleaseComplete(release, archiveCatalog);
}

function schemaErrorPath(error) {
  let path = '$';
  for (const segment of error.instancePath.split('/').slice(1).map(unescapeJsonPointer)) {
    path += /^\d+$/.test(segment) ? `[${segment}]` : `.${segment}`;
  }
  if (error.keyword === 'required') path += `.${error.params.missingProperty}`;
  if (error.keyword === 'additionalProperties') path += `.${error.params.additionalProperty}`;
  return path;
}

function unescapeJsonPointer(value) {
  return value.replaceAll('~1', '/').replaceAll('~0', '~');
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isSafeId(value) {
  return /^[A-Za-z0-9._-]+$/.test(value);
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

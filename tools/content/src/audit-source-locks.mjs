import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { isPlainObject, isSafeLocalPath } from './content-types.mjs';

export async function auditSourceLocks(sourceRoot) {
  const root = resolve(sourceRoot instanceof URL ? fileURLToPath(sourceRoot) : sourceRoot);
  const errors = [];
  let manifest;

  try {
    manifest = JSON.parse(await readFile(resolve(root, 'sources.json'), 'utf8'));
  } catch (error) {
    errors.push(
      error.code === 'ENOENT'
        ? 'sources.json: missing source-lock manifest'
        : 'sources.json: invalid JSON (' + error.message + ')',
    );
    return uniqueSorted(errors);
  }

  if (!isPlainObject(manifest)) {
    return ['sources.json: expected an object'];
  }
  if (!Number.isInteger(manifest.year)) {
    errors.push('sources.json.year: expected an integer');
  }
  if (!Array.isArray(manifest.sources) || manifest.sources.length === 0) {
    errors.push('sources.json.sources: expected at least one source');
    return uniqueSorted(errors);
  }

  const seenIds = new Map();
  for (const [index, source] of manifest.sources.entries()) {
    const path = 'sources.json.sources[' + index + ']';
    if (!isPlainObject(source)) {
      errors.push(path + ': expected an object');
      continue;
    }

    if (typeof source.id !== 'string' || source.id.trim().length === 0) {
      errors.push(path + '.id: expected a non-empty string');
    } else if (seenIds.has(source.id)) {
      errors.push(path + '.id: duplicate source ID ' + source.id + ' also used at index ' + seenIds.get(source.id));
    } else {
      seenIds.set(source.id, index);
    }

    if (typeof source.url !== 'string' || !source.url.startsWith('https://')) {
      errors.push(path + '.url: source citations must use https://');
    }
    if (typeof source.retrievedOn !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(source.retrievedOn)) {
      errors.push(path + '.retrievedOn: expected YYYY-MM-DD');
    }
    if (!Number.isSafeInteger(source.byteLength) || source.byteLength <= 0) {
      errors.push(path + '.byteLength: expected a positive safe integer');
    }
    if (typeof source.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(source.sha256)) {
      errors.push(path + '.sha256: expected a lowercase SHA-256 digest');
    }
    if (source.pageCount !== undefined
        && (!Number.isSafeInteger(source.pageCount) || source.pageCount <= 0)) {
      errors.push(path + '.pageCount: expected a positive integer when provided');
    }

    if (!isSafeLocalPath(source.file)) {
      errors.push(path + '.file: expected a safe local path beneath the year source root');
      continue;
    }

    let bytes;
    try {
      bytes = await readFile(resolve(root, ...source.file.split('/')));
    } catch (error) {
      errors.push(
        error.code === 'ENOENT'
          ? path + '.file: missing locked source ' + source.file
          : path + '.file: cannot read ' + source.file + ' (' + error.message + ')',
      );
      continue;
    }

    if (Number.isSafeInteger(source.byteLength) && bytes.byteLength !== source.byteLength) {
      errors.push(path + '.byteLength: expected ' + source.byteLength + ', found ' + bytes.byteLength);
    }
    if (/^[a-f0-9]{64}$/.test(source.sha256 ?? '')) {
      const digest = createHash('sha256').update(bytes).digest('hex');
      if (digest !== source.sha256) {
        errors.push(path + '.sha256: expected ' + source.sha256 + ', found ' + digest);
      }
    }

    if (source.pageCount !== undefined) {
      if (extname(source.file).toLowerCase() !== '.pdf') {
        errors.push(path + '.pageCount: page counts are supported only for PDF sources');
      } else if (bytes.subarray(0, 5).toString('ascii') !== '%PDF-') {
        errors.push(path + '.pageCount: locked source is not a PDF');
      } else {
        const actualPageCount = [...bytes.toString('latin1').matchAll(/\/Type\s*\/Page\b/g)].length;
        if (actualPageCount !== source.pageCount) {
          errors.push(path + '.pageCount: expected ' + source.pageCount + ', found ' + actualPageCount);
        }
      }
    }
  }

  return uniqueSorted(errors);
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

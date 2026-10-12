import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TextDecoder } from 'node:util';

import { PDFDocument } from 'pdf-lib';

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

    if (typeof source.requestUrl !== 'string' || !source.requestUrl.startsWith('https://')) {
      errors.push(path + '.requestUrl: initial source requests must use https://');
    }
    if (typeof source.url !== 'string' || !source.url.startsWith('https://')) {
      errors.push(path + '.url: source citations must use https://');
    }
    if (!['application/pdf', 'application/x-tex'].includes(source.mediaType)) {
      errors.push(path + '.mediaType: expected application/pdf or application/x-tex');
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
    if (source.mediaType === 'application/pdf'
        && (!Number.isSafeInteger(source.pageCount) || source.pageCount <= 0)) {
      errors.push(path + '.pageCount: a positive parsed page count is required for PDF sources');
    } else if (source.mediaType !== 'application/pdf' && source.pageCount !== undefined) {
      errors.push(path + '.pageCount: page counts are supported only for PDF sources');
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

    const extension = extname(source.file).toLowerCase();
    if (extension === '.pdf' && source.mediaType !== 'application/pdf') {
      errors.push(path + '.mediaType: expected application/pdf for a .pdf source');
    }
    if (extension === '.tex' && source.mediaType !== 'application/x-tex') {
      errors.push(path + '.mediaType: expected application/x-tex for a .tex source');
    }

    if (source.mediaType === 'application/pdf') {
      if (bytes.subarray(0, 5).toString('ascii') !== '%PDF-') {
        errors.push(path + ': locked source is not a PDF');
      } else {
        try {
          const document = await PDFDocument.load(bytes, {
            ignoreEncryption: false,
            updateMetadata: false,
          });
          const actualPageCount = document.getPageCount();
          if (Number.isSafeInteger(source.pageCount) && actualPageCount !== source.pageCount) {
            errors.push(path + '.pageCount: expected ' + source.pageCount + ', found ' + actualPageCount);
          }
        } catch (error) {
          errors.push(path + ': locked PDF cannot be parsed (' + error.message + ')');
        }
      }
    } else if (source.mediaType === 'application/x-tex') {
      try {
        const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
        if (text.length === 0) errors.push(path + ': locked TeX source is empty');
      } catch (error) {
        errors.push(path + ': locked TeX source is not valid UTF-8 (' + error.message + ')');
      }
    }
  }

  return uniqueSorted(errors);
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

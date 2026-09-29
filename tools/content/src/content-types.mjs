import { posix } from 'node:path';

export const SCHEMA_VERSION = 1;
export const BLOCK_TYPES = Object.freeze([
  'paragraph',
  'displayMath',
  'heading',
  'list',
  'figure',
]);

/**
 * @typedef {{type: 'text', text: string} | {type: 'inlineMath', tex: string}} InlineRun
 * @typedef {{blocks: Array<object>}} RichDocument
 * @typedef {{schemaVersion: 1, year: number, code: string, ordinal: number,
 *   statement: RichDocument, hints: Array<object>, prerequisites: Array<object>,
 *   solution: RichDocument, sources: Array<object>, originalPdf: object}} ProblemRecord
 * @typedef {{schemaVersion: 1, year: number, problems: Array<object>, assets: Array<object>}} BuildManifest
 */

export function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function isSafeLocalPath(value) {
  if (typeof value !== 'string' || value.length === 0 || value.includes('\\')) {
    return false;
  }

  if (value.startsWith('/') || /^[a-z][a-z\d+.-]*:/i.test(value)) {
    return false;
  }

  const normalized = posix.normalize(value);
  return normalized === value && normalized !== '..' && !normalized.startsWith('../');
}

export function problemDocuments(record) {
  if (!isPlainObject(record)) {
    return [];
  }

  const documents = [];
  if (isPlainObject(record.statement)) {
    documents.push({ path: '$.statement', document: record.statement });
  }

  if (Array.isArray(record.hints)) {
    record.hints.forEach((hint, index) => {
      if (isPlainObject(hint?.document)) {
        documents.push({ path: `$.hints[${index}].document`, document: hint.document });
      }
    });
  }

  if (Array.isArray(record.prerequisites)) {
    record.prerequisites.forEach((prerequisite, index) => {
      if (isPlainObject(prerequisite?.document)) {
        documents.push({
          path: `$.prerequisites[${index}].document`,
          document: prerequisite.document,
        });
      }
    });
  }

  if (isPlainObject(record.solution)) {
    documents.push({ path: '$.solution', document: record.solution });
  }

  return documents;
}

export function documentFigurePaths(document) {
  if (!Array.isArray(document?.blocks)) {
    return [];
  }

  return document.blocks
    .filter((block) => block?.type === 'figure' && typeof block.path === 'string')
    .map((block) => block.path);
}

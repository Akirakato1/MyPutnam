import { posix } from 'node:path';

export const SCHEMA_VERSION = 3;
export const HINT_STAGES = Object.freeze([
  'nudge',
  'strategy',
  'knowledge',
  'outline',
]);
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
 * @typedef {'nudge' | 'strategy' | 'knowledge' | 'outline'} HintStage
 * @typedef {{order: number, stage: HintStage, title: string, document: RichDocument}} Hint
 * @typedef {{schemaVersion: 3, collectionId: string, setId: string, problemId: string,
 *   label: string, ordinal: number, topics: Array<string>,
 *   statement: RichDocument, hints: Array<object>, prerequisites: Array<object>,
 *   solution: RichDocument, sources: Array<object>}} ProblemRecord
 * @typedef {{schemaVersion: 3, contentVersion: string, collectionId: string, setId: string,
 *   problems: Array<object>, assets: Array<object>, catalogSha256: string,
 *   manifestSha256: string}} BuildManifestV3
 */

const SAFE_ID = /^[A-Za-z0-9._-]+$/;

export function isSafeId(value) {
  return typeof value === 'string'
    && value.length > 0
    && value !== '.'
    && value !== '..'
    && SAFE_ID.test(value);
}

export function setKey(collectionId, setId) {
  requireSafeId(collectionId, 'collectionId');
  requireSafeId(setId, 'setId');
  return `${collectionId}/${setId}`;
}

export function problemKey(collectionId, setId, problemId) {
  requireSafeId(problemId, 'problemId');
  return `${setKey(collectionId, setId)}/${problemId}`;
}

export function parseSetKey(value) {
  const parts = splitCanonicalKey(value, 2, 'set');
  return { collectionId: parts[0], setId: parts[1] };
}

export function parseProblemKey(value) {
  const parts = splitCanonicalKey(value, 3, 'problem');
  return { collectionId: parts[0], setId: parts[1], problemId: parts[2] };
}

function splitCanonicalKey(value, expectedParts, label) {
  if (typeof value !== 'string') throw new TypeError(`${label} key must be a string`);
  const parts = value.split('/');
  if (parts.length !== expectedParts || parts.some((part) => !isSafeId(part))) {
    throw new TypeError(`Invalid canonical ${label} key: ${value}`);
  }
  return parts;
}

function requireSafeId(value, label) {
  if (!isSafeId(value)) throw new TypeError(`${label} is not a safe ID: ${value}`);
}

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

import { readFileSync } from 'node:fs';

import Ajv2020 from 'ajv/dist/2020.js';

export const SCHEMA_IDS = Object.freeze({
  libraryCatalog: 'https://putnamworkbook.local/schema/library-catalog.schema.json',
  packageEnvelope: 'https://putnamworkbook.local/schema/package-envelope.schema.json',
  problem: 'https://putnamworkbook.local/schema/problem.schema.json',
  publicationCatalog: 'https://putnamworkbook.local/schema/publication-catalog.schema.json',
  publicationManifest: 'https://putnamworkbook.local/schema/publication-manifest.schema.json',
  referenceArticle: 'https://putnamworkbook.local/schema/reference-article.schema.json',
  referenceCategories: 'https://putnamworkbook.local/schema/reference-categories.schema.json',
  richDocumentV1: 'https://putnamworkbook.local/schema/rich-document-v1.schema.json',
  richDocumentV2: 'https://putnamworkbook.local/schema/rich-document-v2.schema.json',
});

const schemas = [
  loadSchema('../schema/rich-document-v1.schema.json'),
  loadSchema('../schema/rich-document-v2.schema.json'),
  loadSchema('../schema/reference-categories.schema.json'),
  loadSchema('../schema/library-catalog.schema.json'),
  loadSchema('../schema/problem.schema.json'),
  loadSchema('../schema/reference-article.schema.json'),
  loadSchema('../schema/package-envelope.schema.json'),
  loadSchema('../schema/publication-manifest.schema.json'),
  loadSchema('../schema/publication-catalog.schema.json'),
];
const ajv = new Ajv2020({ allErrors: true, strict: true });
for (const schema of schemas) {
  ajv.addSchema(schema);
}

const problemValidator = requireValidator(SCHEMA_IDS.problem);
const richDocumentValidators = new Map([
  [1, requireValidator(SCHEMA_IDS.richDocumentV1)],
  [2, requireValidator(SCHEMA_IDS.richDocumentV2)],
]);

export function getProblemSchemaValidator() {
  return problemValidator;
}

export function getSchemaValidator(schemaId) {
  return requireValidator(schemaId);
}

export function getRichDocumentSchemaValidator(version) {
  const validator = richDocumentValidators.get(version);
  if (!validator) {
    throw new TypeError(`Unsupported rich-document version: ${version}`);
  }
  return validator;
}

export function formatSchemaErrors(errors, basePath = '$') {
  return (errors ?? []).map((error) => {
    let path = basePath;
    const segments = error.instancePath.split('/').slice(1).map(unescapeJsonPointer);
    for (const segment of segments) {
      path += /^\d+$/.test(segment) ? `[${segment}]` : `.${segment}`;
    }
    if (error.keyword === 'required') {
      path += `.${error.params.missingProperty}`;
    }
    if (error.keyword === 'additionalProperties') {
      path += `.${error.params.additionalProperty}`;
    }
    return `${path}: ${error.message}`;
  });
}

function loadSchema(relativePath) {
  return JSON.parse(readFileSync(new URL(relativePath, import.meta.url), 'utf8'));
}

function requireValidator(schemaId) {
  const validator = ajv.getSchema(schemaId);
  if (!validator) {
    throw new Error(`Schema did not compile: ${schemaId}`);
  }
  return validator;
}

function unescapeJsonPointer(value) {
  return value.replaceAll('~1', '/').replaceAll('~0', '~');
}

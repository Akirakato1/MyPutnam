import { parseProblemKey, parseReferenceKey } from './content-types.mjs';
import { validateRichDocument } from './rich-document.mjs';
import { formatSchemaErrors, getSchemaValidator, SCHEMA_IDS } from './schema-registry.mjs';

const validateSchema = getSchemaValidator(SCHEMA_IDS.referenceArticle);

export function validateReferenceArticle(value, { categories = [] } = {}) {
  const errors = [];
  if (!validateSchema(value)) errors.push(...formatSchemaErrors(validateSchema.errors));
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return uniqueSorted(errors);

  if (!categories.some((category) => category?.id === value.categoryId)) {
    errors.push(`$.categoryId: unknown reference category ${value.categoryId}`);
  }

  for (const [field, document] of [
    ['notation', value.notation], ['definitions', value.definitions], ['results', value.results],
    ['conditions', value.conditions], ['pitfalls', value.pitfalls],
  ]) {
    if (document !== undefined) errors.push(...validateRichDocument(document, { version: 2, basePath: `$.${field}` }));
  }
  for (const [index, example] of (value.examples ?? []).entries()) {
    if (example?.document !== undefined) {
      errors.push(...validateRichDocument(example.document, { version: 2, basePath: `$.examples[${index}].document` }));
    }
  }

  validateSearchTerms(value.aliases, value.keywords, errors);
  validateKeys(value.relatedReferences, '$.relatedReferences', parseReferenceKey, errors);
  validateKeys(value.relatedProblems, '$.relatedProblems', parseProblemKey, errors);
  return uniqueSorted(errors);
}

function validateSearchTerms(aliases = [], keywords = [], errors) {
  const aliasesSeen = new Set();
  for (const [index, alias] of aliases.entries()) {
    const normalized = normalizeTerm(alias);
    if (aliasesSeen.has(normalized)) errors.push(`$.aliases[${index}]: duplicate alias ${alias}`);
    aliasesSeen.add(normalized);
  }
  const keywordsSeen = new Set();
  for (const [index, keyword] of keywords.entries()) {
    const normalized = normalizeTerm(keyword);
    if (aliasesSeen.has(normalized) || keywordsSeen.has(normalized)) {
      errors.push(`$.keywords[${index}]: ${keyword} duplicates an alias or keyword`);
    }
    keywordsSeen.add(normalized);
  }
}

function validateKeys(values = [], path, parser, errors) {
  const seen = new Set();
  for (const [index, value] of values.entries()) {
    try { parser(value); } catch { errors.push(`${path}[${index}]: invalid canonical relation key ${value}`); }
    if (seen.has(value)) errors.push(`${path}[${index}]: duplicate relation key ${value}`);
    seen.add(value);
  }
}

function normalizeTerm(value) {
  return typeof value === 'string' ? value.trim().toLocaleLowerCase('en-US') : value;
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

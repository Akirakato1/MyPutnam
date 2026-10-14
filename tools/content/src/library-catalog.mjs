import { readFile } from 'node:fs/promises';

import Ajv2020 from 'ajv/dist/2020.js';

import { isPlainObject, isSafeId } from './content-types.mjs';

const schema = JSON.parse(await readFile(new URL('../schema/library-catalog.schema.json', import.meta.url), 'utf8'));
const validateSchema = new Ajv2020({ allErrors: true, strict: true }).compile(schema);

export function validateLibraryCatalog(value) {
  const errors = [];
  if (!validateSchema(value)) {
    for (const error of validateSchema.errors ?? []) errors.push(`${schemaPath(error)}: ${error.message}`);
  }
  if (!isPlainObject(value)) return uniqueSorted(errors);

  const collectionIds = new Map();
  const collectionOrders = new Map();
  for (const [collectionIndex, collection] of (value.collections ?? []).entries()) {
    const path = `$.collections[${collectionIndex}]`;
    validateId(collection?.id, `${path}.id`, errors);
    addUnique(collectionIds, collection?.id, `${path}.id`, 'collection id', errors);
    addUnique(collectionOrders, collection?.displayOrder, `${path}.displayOrder`, 'collection display order', errors);
    const setIds = new Map();
    const setOrders = new Map();
    for (const [setIndex, set] of (collection?.sets ?? []).entries()) {
      const setPath = `${path}.sets[${setIndex}]`;
      validateId(set?.id, `${setPath}.id`, errors);
      addUnique(setIds, set?.id, `${setPath}.id`, 'set id', errors);
      addUnique(setOrders, set?.displayOrder, `${setPath}.displayOrder`, 'set display order', errors);
      const competitionYear = set?.metadata?.competitionYear;
      const administrationYear = set?.metadata?.administrationYear;
      if (Number.isInteger(competitionYear) && Number.isInteger(administrationYear)
          && administrationYear < competitionYear) {
        errors.push(`${setPath}.metadata.administrationYear: cannot be earlier than competitionYear`);
      }
    }
  }

  const topicIds = new Map();
  const topicOrders = new Map();
  for (const [topicIndex, topic] of (value.topics ?? []).entries()) {
    const path = `$.topics[${topicIndex}]`;
    validateId(topic?.id, `${path}.id`, errors);
    addUnique(topicIds, topic?.id, `${path}.id`, 'topic id', errors);
    addUnique(topicOrders, topic?.displayOrder, `${path}.displayOrder`, 'topic display order', errors);
  }
  return uniqueSorted(errors);
}

export async function loadLibraryCatalog(path) {
  let value;
  try {
    value = JSON.parse(await readFile(path, 'utf8'));
  } catch (error) {
    throw new Error(`Cannot load library catalog ${path}: ${error.message}`, { cause: error });
  }
  const errors = validateLibraryCatalog(value);
  if (errors.length > 0) {
    throw new Error(`Library catalog validation failed:\n${errors.map((error) => `- ${error}`).join('\n')}`);
  }
  return value;
}

export function findCatalogSet(catalog, collectionId, setId) {
  const collection = catalog?.collections?.find((candidate) => candidate.id === collectionId);
  const problemSet = collection?.sets?.find((candidate) => candidate.id === setId);
  return collection && problemSet ? { collection, problemSet } : null;
}

function validateId(value, path, errors) {
  if (typeof value === 'string' && !isSafeId(value)) errors.push(`${path}: unsafe ID`);
}

function addUnique(seen, value, path, label, errors) {
  if (value === undefined) return;
  if (seen.has(value)) errors.push(`${path}: duplicate ${label} ${value}`);
  else seen.set(value, path);
}

function schemaPath(error) {
  let path = '$' + error.instancePath.replaceAll('/', '.');
  if (error.keyword === 'required') path += `.${error.params.missingProperty}`;
  if (error.keyword === 'additionalProperties') path += `.${error.params.additionalProperty}`;
  return path;
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

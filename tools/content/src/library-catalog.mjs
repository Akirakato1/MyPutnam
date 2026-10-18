import { readFile } from 'node:fs/promises';

import { isPlainObject, isSafeId } from './content-types.mjs';
import { formatSchemaErrors, getSchemaValidator, SCHEMA_IDS } from './schema-registry.mjs';

const validateSchema = getSchemaValidator(SCHEMA_IDS.libraryCatalog);

export function validateLibraryCatalog(value) {
  const errors = [];
  if (!validateSchema(value)) {
    errors.push(...formatSchemaErrors(validateSchema.errors));
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
  const categoryIds = new Map();
  const categoryOrders = new Map();
  for (const [categoryIndex, category] of (value.referenceCategories ?? []).entries()) {
    const path = `$.referenceCategories[${categoryIndex}]`;
    validateId(category?.id, `${path}.id`, errors);
    addUnique(categoryIds, category?.id, `${path}.id`, 'reference category id', errors);
    addUnique(categoryOrders, category?.displayOrder, `${path}.displayOrder`, 'reference category display order', errors);
    addCaseInsensitiveUnique(category?.aliases, `${path}.aliases`, 'reference category alias', errors);
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

function addCaseInsensitiveUnique(values, path, label, errors) {
  const seen = new Set();
  for (const [index, value] of (values ?? []).entries()) {
    const folded = typeof value === 'string' ? value.trim().toLocaleLowerCase('en-US') : value;
    if (seen.has(folded)) errors.push(`${path}[${index}]: duplicate ${label} ${value}`);
    seen.add(folded);
  }
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

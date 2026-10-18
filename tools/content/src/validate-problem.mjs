import {
  HINT_STAGES,
  isPlainObject,
  isSafeId,
  problemDocuments,
} from './content-types.mjs';
import { validateLibraryCatalog } from './library-catalog.mjs';
import { findRawMathInListText } from './list-math-audit.mjs';
import { validateRichDocument } from './rich-document.mjs';
import {
  formatSchemaErrors,
  getProblemSchemaValidator,
} from './schema-registry.mjs';

const validateSchema = getProblemSchemaValidator();

export function validateProblem(record, catalog) {
  const errors = [];

  if (catalog === undefined) {
    errors.push('$catalog: library catalog is required');
  } else {
    errors.push(...validateLibraryCatalog(catalog).map((error) => `$catalog${error.slice(1)}`));
  }

  const schemaValid = validateSchema(record);
  if (!schemaValid) {
    errors.push(...formatSchemaErrors(validateSchema.errors));
  }

  if (!isPlainObject(record)) {
    return uniqueSorted(errors);
  }

  for (const field of ['collectionId', 'setId', 'problemId']) {
    if (typeof record[field] === 'string' && !isSafeId(record[field])) {
      errors.push(`$.${field}: unsafe ID`);
    }
  }
  if (catalog !== undefined) {
    const collection = catalog.collections?.find((candidate) => candidate.id === record.collectionId);
    if (!collection) errors.push(`$.collectionId: unknown collection ${record.collectionId}`);
    else if (!collection.sets?.some((candidate) => candidate.id === record.setId)) {
      errors.push(`$.setId: unknown set ${record.setId} in collection ${record.collectionId}`);
    }
    const knownTopics = new Set((catalog.topics ?? []).map(({ id }) => id));
    const seenTopics = new Set();
    for (const [index, topic] of (record.topics ?? []).entries()) {
      if (seenTopics.has(topic)) errors.push(`$.topics[${index}]: duplicate topic ${topic}`);
      seenTopics.add(topic);
      if (!knownTopics.has(topic)) errors.push(`$.topics[${index}]: unknown topic ${topic}`);
    }
  }

  validateOrderedItems(record.hints, '$.hints', [1, 2, 3, 4], errors);
  validateHintStages(record.hints, errors);
  if (Array.isArray(record.prerequisites)) {
    validateOrderedItems(
      record.prerequisites,
      '$.prerequisites',
      record.prerequisites.map((_, index) => index + 1),
      errors,
    );
  }

  for (const { path, document } of problemDocuments(record)) {
    errors.push(...validateRichDocument(document, { version: 1, basePath: path }));
    if (schemaValid) {
      for (const finding of findRawMathInListText(document, path)) {
        errors.push(`${finding.path}: mathematical notation in list text must use inlineMath (${finding.rule})`);
      }
    }
  }

  return uniqueSorted(errors);
}

function validateHintStages(hints, errors) {
  if (!Array.isArray(hints)) {
    return;
  }

  const seen = new Set();
  hints.forEach((hint, index) => {
    const stage = hint?.stage;
    if (typeof stage !== 'string') {
      return;
    }
    if (seen.has(stage)) {
      errors.push(`$.hints[${index}].stage: duplicate stage ${stage}`);
    }
    seen.add(stage);
    if (stage !== HINT_STAGES[index]) {
      errors.push(`$.hints[${index}].stage: expected ${HINT_STAGES[index]}`);
    }
  });
}

function validateOrderedItems(items, path, expectedOrders, errors) {
  if (!Array.isArray(items)) {
    return;
  }

  const seen = new Map();
  items.forEach((item, index) => {
    const order = item?.order;
    if (!Number.isInteger(order)) {
      return;
    }
    if (seen.has(order)) {
      errors.push(`${path}[${index}].order: duplicate order ${order}`);
    } else {
      seen.set(order, index);
    }
  });

  items.forEach((item, index) => {
    if (Number.isInteger(item?.order) && item.order !== expectedOrders[index]) {
      errors.push(`${path}[${index}].order: expected ${expectedOrders[index]}`);
    }
  });
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

import { readFileSync } from 'node:fs';

import Ajv2020 from 'ajv/dist/2020.js';
import katex from 'katex';

import {
  HINT_STAGES,
  isPlainObject,
  isSafeLocalPath,
  problemDocuments,
} from './content-types.mjs';

const schema = JSON.parse(
  readFileSync(new URL('../schema/problem.schema.json', import.meta.url), 'utf8'),
);
const ajv = new Ajv2020({ allErrors: true, strict: true });
const validateSchema = ajv.compile(schema);
const unsafeTexCommand = /\\(?:href|url|includegraphics|html(?:Class|Data|Id|Style)?|class|id|style)\b/;

export function validateProblem(record) {
  const errors = [];

  if (!validateSchema(record)) {
    for (const error of validateSchema.errors ?? []) {
      errors.push(`${schemaErrorPath(error)}: ${error.message}`);
    }
  }

  if (!isPlainObject(record)) {
    return uniqueSorted(errors);
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
    validateDocument(document, path, errors);
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

function validateDocument(document, path, errors) {
  if (!Array.isArray(document.blocks)) {
    return;
  }

  document.blocks.forEach((block, blockIndex) => {
    const blockPath = `${path}.blocks[${blockIndex}]`;
    if (!isPlainObject(block)) {
      return;
    }

    if (block.type === 'displayMath') {
      validateTex(block.tex, `${blockPath}.tex`, true, errors);
    }

    if (block.type === 'paragraph' || block.type === 'heading') {
      validateRuns(block.runs, `${blockPath}.runs`, errors);
    }

    if (block.type === 'list' && Array.isArray(block.items)) {
      block.items.forEach((runs, itemIndex) => {
        validateRuns(runs, `${blockPath}.items[${itemIndex}]`, errors);
      });
    }

    if (block.type === 'figure') {
      if (!isSafeLocalPath(block.path)) {
        errors.push(`${blockPath}.path: must be a normalized local path beneath the problem directory`);
      }
      if (block.caption !== undefined) {
        validateRuns(block.caption, `${blockPath}.caption`, errors);
      }
    }
  });
}

function validateRuns(runs, path, errors) {
  if (!Array.isArray(runs)) {
    return;
  }

  runs.forEach((run, index) => {
    if (run?.type === 'inlineMath') {
      validateTex(run.tex, `${path}[${index}].tex`, false, errors);
    }
  });
}

function validateTex(tex, path, displayMode, errors) {
  if (typeof tex !== 'string') {
    return;
  }

  if (unsafeTexCommand.test(tex)) {
    errors.push(`${path}: contains a command that can reference or style external content`);
    return;
  }

  try {
    katex.renderToString(tex, {
      displayMode,
      output: 'htmlAndMathml',
      strict: 'error',
      throwOnError: true,
      trust: false,
    });
  } catch (error) {
    errors.push(`${path}: invalid TeX (${error.message})`);
  }
}

function schemaErrorPath(error) {
  let path = '$';
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

  return path;
}

function unescapeJsonPointer(value) {
  return value.replaceAll('~1', '/').replaceAll('~0', '~');
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

import katex from 'katex';

import { isPlainObject, isSafeLocalPath } from './content-types.mjs';
import {
  formatSchemaErrors,
  getRichDocumentSchemaValidator,
} from './schema-registry.mjs';

const unsafeTexCommand = /\\(?:href|url|includegraphics|html(?:Class|Data|Id|Style)?|class|id|style)\b/;

export function validateRichDocument(document, { version, basePath = '$' } = {}) {
  const validateSchema = getRichDocumentSchemaValidator(version);
  if (!validateSchema(document)) {
    return uniqueSorted(formatSchemaErrors(validateSchema.errors, basePath));
  }

  const errors = [];
  visitDocument(document, basePath, ({ block, path }) => {
    if (block.type === 'figure' && !isSafeLocalPath(block.path)) {
      errors.push(`${path}.path: must be a normalized local path beneath the problem directory`);
    }
    if (block.type === 'table') {
      const expectedCells = block.headers.length;
      block.rows.forEach((row, rowIndex) => {
        if (row.length !== expectedCells) {
          errors.push(`${path}.rows[${rowIndex}]: expected ${expectedCells} cell(s), found ${row.length}`);
        }
      });
    }
  });

  for (const { path, tex, displayMode } of findDocumentMathRuns(document, basePath)) {
    validateTex(tex, path, displayMode, errors);
  }
  return uniqueSorted(errors);
}

export function findDocumentMathRuns(document, basePath = '$') {
  const mathRuns = [];
  visitDocument(document, basePath, ({ block, path: blockPath }) => {
    if (block.type === 'displayMath' && typeof block.tex === 'string') {
      mathRuns.push({ path: `${blockPath}.tex`, tex: block.tex, displayMode: true });
    }
    if (block.type === 'paragraph' || block.type === 'heading') {
      appendMathRuns(block.runs, `${blockPath}.runs`, mathRuns);
    }
    if (block.type === 'list' && Array.isArray(block.items)) {
      block.items.forEach((runs, itemIndex) => {
        appendMathRuns(runs, `${blockPath}.items[${itemIndex}]`, mathRuns);
      });
    }
    if (block.type === 'figure' && block.caption !== undefined) {
      appendMathRuns(block.caption, `${blockPath}.caption`, mathRuns);
    }
    if (block.type === 'table') {
      appendMathRuns(block.caption, `${blockPath}.caption`, mathRuns);
      if (Array.isArray(block.headers)) {
        block.headers.forEach((runs, headerIndex) => {
          appendMathRuns(runs, `${blockPath}.headers[${headerIndex}]`, mathRuns);
        });
      }
    }
  });
  return mathRuns;
}

function visitDocument(document, basePath, visitor) {
  if (!Array.isArray(document?.blocks)) {
    return;
  }
  document.blocks.forEach((block, blockIndex) => {
    if (!isPlainObject(block)) {
      return;
    }
    const blockPath = `${basePath}.blocks[${blockIndex}]`;
    visitor({ block, path: blockPath });
    if (block.type === 'table' && Array.isArray(block.rows)) {
      block.rows.forEach((row, rowIndex) => {
        if (!Array.isArray(row)) {
          return;
        }
        row.forEach((cell, columnIndex) => {
          visitDocument(cell, `${blockPath}.rows[${rowIndex}][${columnIndex}]`, visitor);
        });
      });
    }
  });
}

function appendMathRuns(runs, path, output) {
  if (!Array.isArray(runs)) {
    return;
  }
  runs.forEach((run, index) => {
    if (run?.type === 'inlineMath' && typeof run.tex === 'string') {
      output.push({ path: `${path}[${index}].tex`, tex: run.tex, displayMode: false });
    }
  });
}

function validateTex(tex, path, displayMode, errors) {
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

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

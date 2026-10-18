import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { CANONICAL_PROBLEMS } from '../src/audit-year.mjs';
import {
  parseReferenceKey,
  problemDocuments,
  referenceKey,
} from '../src/content-types.mjs';
import {
  findDocumentMathRuns,
  validateRichDocument,
} from '../src/rich-document.mjs';

const fixtures = new URL('./fixtures/contracts/', import.meta.url);
const sourceRoot = fileURLToPath(new URL('../../../content-src/', import.meta.url));

async function fixture(name) {
  return JSON.parse(await readFile(new URL(name, fixtures), 'utf8'));
}

test('reference keys are canonical, case-sensitive, and path-safe', () => {
  assert.equal(referenceKey('Number-Theory', 'Euler-Totient'),
    'math-reference/Number-Theory/Euler-Totient');
  assert.deepEqual(parseReferenceKey('math-reference/Number-Theory/Euler-Totient'), {
    namespace: 'math-reference', categoryId: 'Number-Theory', articleId: 'Euler-Totient',
  });
  assert.notEqual(referenceKey('Number-Theory', 'Euler-Totient'),
    referenceKey('number-theory', 'euler-totient'));

  for (const args of [
    ['', 'article'], ['.', 'article'], ['category', '..'],
    ['category/name', 'article'], ['category', 'article\\name'], ['C:', 'article'],
  ]) {
    assert.throws(() => referenceKey(...args), TypeError, args.join('/'));
  }
  for (const value of [
    'reference/number-theory/euler-totient',
    'math-reference/number-theory',
    'math-reference/number-theory/euler-totient/extra',
    'math-reference/number-theory/..',
  ]) {
    assert.throws(() => parseReferenceKey(value), TypeError, value);
  }
});

test('rich-document v1 accepts every current problem document unchanged', async () => {
  let recordCount = 0;
  let documentCount = 0;
  for (const year of [2020, 2021, 2022, 2023, 2024, 2025]) {
    for (const { code } of CANONICAL_PROBLEMS) {
      const record = JSON.parse(await readFile(
        join(sourceRoot, String(year), 'problems', `${code}.json`), 'utf8',
      ));
      recordCount += 1;
      for (const { path, document } of problemDocuments(record)) {
        documentCount += 1;
        assert.deepEqual(validateRichDocument(document, { version: 1, basePath: path }), [],
          `putnam/${year}/${code} ${path}`);
      }
    }
  }
  assert.equal(recordCount, 72);
  assert.equal(documentCount, 651);
});

test('rich-document versions enforce the table boundary', async () => {
  const v1 = await fixture('valid-rich-document-v1.json');
  const v2 = await fixture('valid-rich-document-v2.json');
  assert.deepEqual(validateRichDocument(v1, { version: 1, basePath: '$.article' }), []);
  assert.deepEqual(validateRichDocument(v1, { version: 2, basePath: '$.article' }), []);
  assert.ok(validateRichDocument(v2, { version: 1, basePath: '$.article' })
    .some((error) => error.startsWith('$.article.blocks[1]')));
  assert.deepEqual(validateRichDocument(v2, { version: 2, basePath: '$.article' }), []);
  assert.throws(() => validateRichDocument(v1, { version: 3 }), /Unsupported rich-document version/);
});

test('rich-document v2 rejects empty, ragged, nested, and invalid-math tables', async () => {
  const emptyRows = await fixture('invalid-rich-document-v2.json');
  assert.ok(validateRichDocument(emptyRows, { version: 2, basePath: '$.article' })
    .some((error) => error.startsWith('$.article.blocks[0].rows:')));

  const emptyColumns = await fixture('valid-rich-document-v2.json');
  emptyColumns.blocks[1].headers = [];
  emptyColumns.blocks[1].rows = [[]];
  assert.ok(validateRichDocument(emptyColumns, { version: 2, basePath: '$.article' })
    .some((error) => error.startsWith('$.article.blocks[1].headers:')));

  const ragged = await fixture('valid-rich-document-v2.json');
  ragged.blocks[1].rows[1].pop();
  assert.ok(validateRichDocument(ragged, { version: 2, basePath: '$.article' })
    .includes('$.article.blocks[1].rows[1]: expected 2 cell(s), found 1'));

  const nested = await fixture('valid-rich-document-v2.json');
  nested.blocks[1].rows[0][0].blocks = [structuredClone(nested.blocks[1])];
  assert.ok(validateRichDocument(nested, { version: 2, basePath: '$.article' })
    .some((error) => error.startsWith('$.article.blocks[1].rows[0][0].blocks[0]')));

  const invalidMath = await fixture('valid-rich-document-v2.json');
  invalidMath.blocks[1].rows[0][0].blocks[0].runs[0].tex = String.raw`\notacommand{`;
  assert.ok(validateRichDocument(invalidMath, { version: 2, basePath: '$.article' })
    .some((error) => error.startsWith('$.article.blocks[1].rows[0][0].blocks[0].runs[0].tex: invalid TeX')));
});

test('math traversal includes table captions, headers, and cell documents with exact paths', async () => {
  const v2 = await fixture('valid-rich-document-v2.json');
  assert.deepEqual(findDocumentMathRuns(v2, '$.article').map(({ path, displayMode }) => ({
    path, displayMode,
  })), [
    { path: '$.article.blocks[1].headers[1][1].tex', displayMode: false },
    { path: '$.article.blocks[1].rows[0][0].blocks[0].runs[0].tex', displayMode: false },
    { path: '$.article.blocks[1].rows[0][1].blocks[0].tex', displayMode: true },
    { path: '$.article.blocks[1].rows[1][0].blocks[0].runs[0].tex', displayMode: false },
    { path: '$.article.blocks[1].rows[1][1].blocks[0].runs[0].tex', displayMode: false },
  ]);
});

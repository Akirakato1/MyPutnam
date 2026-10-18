import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { validateReferenceArticle } from '../src/validate-reference.mjs';

const fixtures = new URL('./fixtures/contracts/', import.meta.url);
const fixture = async (name) => JSON.parse(await readFile(new URL(name, fixtures), 'utf8'));
const categories = [{ id: 'number-theory', label: 'Number Theory', displayOrder: 1, aliases: ['NT'] }];

test('reference contract accepts complete structured articles', async () => {
  assert.deepEqual(validateReferenceArticle(await fixture('valid-reference.json'), { categories }), []);
});

test('reference contract requires every lookup section, an example, and complete rights', async () => {
  const value = await fixture('valid-reference.json');
  delete value.conditions;
  value.examples = [];
  delete value.rights.licenseBasis;
  const errors = validateReferenceArticle(value, { categories });
  assert.ok(errors.some((error) => error.startsWith('$.conditions:')));
  assert.ok(errors.some((error) => error.startsWith('$.examples:')));
  assert.ok(errors.some((error) => error.startsWith('$.rights.licenseBasis:')));
});

test('reference contract rejects unknown categories and unsafe relation keys', async () => {
  const value = await fixture('valid-reference.json');
  value.categoryId = 'unknown';
  value.relatedReferences = ['math-reference/number-theory/../escape'];
  value.relatedProblems = ['putnam/2025'];
  const errors = validateReferenceArticle(value, { categories });
  assert.ok(errors.includes('$.categoryId: unknown reference category unknown'));
  assert.ok(errors.some((error) => error.startsWith('$.relatedReferences[0]:')));
  assert.ok(errors.some((error) => error.startsWith('$.relatedProblems[0]:')));
});

test('reference contract rejects duplicate display identity and search terms', async () => {
  const value = await fixture('valid-reference.json');
  value.aliases.push('PHI');
  value.keywords.push('Coprime');
  const errors = validateReferenceArticle(value, { categories });
  assert.ok(errors.some((error) => error.includes('duplicate alias')));
  assert.ok(errors.some((error) => error.includes('duplicates an alias or keyword')));
});

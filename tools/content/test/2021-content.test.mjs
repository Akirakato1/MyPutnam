import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { auditYear } from '../src/audit-year.mjs';

const yearRoot = new URL('../../../content-src/2021/', import.meta.url);

test('the complete 2021 corpus passes the reusable year audit', async () => {
  assert.deepEqual(await auditYear(yearRoot, 2021), []);
});

test('the 2021 B2 prerequisite states the nonnegative AM-GM equality case correctly', async () => {
  const record = JSON.parse(await readFile(new URL('problems/B2.json', yearRoot), 'utf8'));
  const prerequisite = JSON.stringify(record.prerequisites.find(({ order }) => order === 1));

  assert.doesNotMatch(prerequisite, /all positive entries are equal/);
  assert.match(prerequisite, /all entries are equal/);
});

test('the 2021 B6 statement preserves the first-trim cardinality', async () => {
  const statement = await readFile(new URL('problems/B6.json', yearRoot), 'utf8');

  assert.match(statement, /3\^\{2020\}/);
});

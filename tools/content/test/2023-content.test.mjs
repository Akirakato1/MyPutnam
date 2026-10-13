import assert from 'node:assert/strict';
import test from 'node:test';

import { auditYear } from '../src/audit-year.mjs';

const yearRoot = new URL('../../../content-src/2023/', import.meta.url);

test('the complete 2023 corpus passes the reusable year audit', async () => {
  assert.deepEqual(await auditYear(yearRoot, 2023), []);
});

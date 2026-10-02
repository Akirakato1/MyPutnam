import assert from 'node:assert/strict';
import test from 'node:test';

import { auditSourceLocks } from '../src/audit-source-locks.mjs';

const yearRoot = new URL('../../../content-src/2025/', import.meta.url);

test('the locked 2025 source set passes the reusable source audit', async () => {
  assert.deepEqual(await auditSourceLocks(yearRoot), []);
});

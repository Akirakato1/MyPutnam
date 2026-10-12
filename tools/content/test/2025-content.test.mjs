import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { auditYear } from '../src/audit-year.mjs';

const yearRoot = new URL('../../../content-src/2025/', import.meta.url);

test('the complete 2025 corpus passes the reusable year audit', async () => {
  assert.deepEqual(await auditYear(yearRoot, 2025), []);
});

test('every 2025 review records prerequisite and narrow/wide render evidence', async () => {
  for (const problemId of ['A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'B1', 'B2', 'B3', 'B4', 'B5', 'B6']) {
    const review = await readFile(new URL(`review/${problemId}.md`, yearRoot), 'utf8');
    assert.match(review, /Prerequisite review: complete/);
    assert.match(review, /Render review: complete/);
    assert.match(review, /480 CSS px/);
    assert.match(review, /720 CSS px/);
  }
});

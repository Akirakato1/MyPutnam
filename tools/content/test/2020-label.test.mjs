import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';

import { auditYear } from '../src/audit-year.mjs';
import { buildYear } from '../src/build-year.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';
import { createYearFixture } from './helpers/year-fixture.mjs';

const catalogUrl = new URL('../../../content-src/library.json', import.meta.url);

test('the delayed sitting keeps 2020 in audit, preview heading, manifest, and canonical key', async (t) => {
  const { fixtureRoot, sourceRoot } = await createYearFixture(t, 2020);
  const catalog = await loadLibraryCatalog(catalogUrl);

  assert.deepEqual(await auditYear(sourceRoot, 2020, {
    catalog,
    problems: ['A1'],
    requireCompleteYear: false,
    requireRenderReview: true,
  }), []);

  const outputRoot = join(fixtureRoot, 'preview');
  const manifest = await buildYear(sourceRoot, outputRoot, {
    year: 2020,
    contentVersion: '2020.delayed-label-test',
    catalog,
    problems: ['A1'],
  });

  assert.equal(manifest.setId, '2020');
  assert.equal(manifest.problems[0].key, 'putnam/2020/A1');
  const statementPath = join(outputRoot, ...manifest.problems[0].statement.assetPath.split('/'));
  const statementHtml = await readFile(statementPath, 'utf8');
  assert.match(statementHtml, /<title>William Lowell Putnam Mathematical Competition 2020 A1<\/title>/);
  assert.doesNotMatch(statementHtml, /Competition 2021 A1/);
});

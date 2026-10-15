import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { buildYear } from '../src/build-year.mjs';
import { problemDocuments } from '../src/content-types.mjs';
import { auditRenderedDocument } from '../src/render-document.mjs';

const sourceRoot = fileURLToPath(new URL('../../../content-src/', import.meta.url));
const years = [2020, 2021, 2022, 2023, 2024, 2025];
const prerequisiteCounts = new Map([
  [2020, 40],
  [2021, 35],
  [2022, 36],
  [2023, 36],
  [2024, 36],
  [2025, 36],
]);

test('generated 2020-2025 content preserves stages and renders every source math run', async () => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), 'putnam-generated-audit-'));

  try {
    for (const year of years) {
      const yearSourceRoot = join(sourceRoot, String(year));
      const outputRoot = join(temporaryRoot, 'content', String(year));
      const manifest = await buildYear(yearSourceRoot, outputRoot, {
        year,
        contentVersion: `${year}.2`,
      });

      assert.equal(manifest.problems.length, 12, `${year}: problem count`);
      assert.equal(manifest.problems.flatMap(({ hints }) => hints).length, 48, `${year}: hint count`);
      assert.equal(
        manifest.problems.flatMap(({ prerequisites }) => prerequisites).length,
        prerequisiteCounts.get(year),
        `${year}: prerequisite count`,
      );
      assert.ok(manifest.problems.every(({ topics }) => topics.length > 0));
      assert.equal(manifest.assets.some(({ path }) => path.endsWith('.pdf')), false);

      for (const generatedProblem of manifest.problems) {
        const source = JSON.parse(
          await readFile(join(yearSourceRoot, 'problems', `${generatedProblem.problemId}.json`), 'utf8'),
        );
        assert.deepEqual(
          generatedProblem.hints.map(({ stage }) => stage),
          source.hints.map(({ stage }) => stage),
        );

        for (const { path, document } of problemDocuments(source)) {
          const reference = generatedReference(generatedProblem, path);
          const html = await readFile(join(outputRoot, ...reference.assetPath.split('/')), 'utf8');
          auditRenderedDocument(document, html, `${year}/${generatedProblem.problemId}${path}`);
        }
      }
    }
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

function generatedReference(problem, sourcePath) {
  if (sourcePath === '$.statement') return problem.statement;
  if (sourcePath === '$.solution') return problem.solution;

  const hint = sourcePath.match(/^\$\.hints\[(\d+)]\.document$/);
  if (hint) return problem.hints[Number(hint[1])];

  const prerequisite = sourcePath.match(/^\$\.prerequisites\[(\d+)]\.document$/);
  if (prerequisite) return problem.prerequisites[Number(prerequisite[1])];

  throw new Error(`No generated reference for ${sourcePath}`);
}

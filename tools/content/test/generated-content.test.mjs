import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { buildYear } from '../src/build-year.mjs';
import { problemDocuments } from '../src/content-types.mjs';
import { auditRenderedDocument } from '../src/render-document.mjs';

const sourceRoot = fileURLToPath(new URL('../../../content-src/2025/', import.meta.url));

test('generated 2025 content preserves stages and renders every source math run', async () => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), 'putnam-generated-audit-'));
  const outputRoot = join(temporaryRoot, 'content');

  try {
    const manifest = await buildYear(sourceRoot, outputRoot, {
      year: 2025,
      contentVersion: '2025.2',
    });

    assert.equal(manifest.problems.length, 12);
    assert.equal(manifest.problems.flatMap(({ hints }) => hints).length, 48);
    assert.equal(manifest.assets.some(({ path }) => path.endsWith('.pdf')), false);

    for (const generatedProblem of manifest.problems) {
      const source = JSON.parse(
        await readFile(join(sourceRoot, 'problems', `${generatedProblem.code}.json`), 'utf8'),
      );
      assert.deepEqual(
        generatedProblem.hints.map(({ stage }) => stage),
        source.hints.map(({ stage }) => stage),
      );

      for (const { path, document } of problemDocuments(source)) {
        const reference = generatedReference(generatedProblem, path);
        const html = await readFile(join(outputRoot, ...reference.assetPath.split('/')), 'utf8');
        auditRenderedDocument(document, html, `${generatedProblem.code}${path}`);
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

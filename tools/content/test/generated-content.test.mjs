import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';

import { chromium } from 'playwright';

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

// Hand-checked fourth-hint formulas, including every list run repaired in Task 2.
const repairedHintLists = [
  [2024, 'A3', [['S', String.raw`3\times n`], ['T(2,1)=2'],
    ['f(n,n-1,n-1)/f(n,n,n)'], ['n=2024']]],
  [2025, 'A4', [['k=1'], ['k=2'], [String.raw`\mathbb R^3`], ['A_iA_j=A_jA_i']]],
  [2025, 'A5', [['(-1,-1)'], [], [], []]],
  [2025, 'A6', [['c_{n,k}', '2^{k+1}', '2^{k+2}'],
    ['d_{n,k}', '2^{2k+2}', '2^{2k+3}'], ['n=0']]],
  [2025, 'B1', [['R', 'R'], ['G', 'G'], ['6+6<18']]],
  [2025, 'B2', [['x_2-x_1'], ['x-y', 'f(x)-f(y)'], []]],
  [2025, 'B3', [['a=b=0', String.raw`k<c\le n`], ['a+b>0', 'm=(a+b)k'],
    ['m<n', 'a+b<3^a5^b', 'k<c'], ['n', '2025^m-15^m']]],
  [2025, 'B4', [['r', "N=N'+r", "S=N+S'"], [String.raw`r\ge n`, '(n-1)'], ['n(n+1)/2']]],
  [2025, 'B5', [['t'], ['p-3', 't'], ['I(k)', 'I(k+1)', '(p-3)/4']]],
  [2025, 'B6', [['n', String.raw`g(n)\ge n+2`, String.raw`g(g(n))\ge g(n+2)`],
    ['n', 'n+1', 'g(n+2)', 'g(n+1)', 'g(n+1)'], []]],
];

test('generated repaired fourth hints render each formula inside its ordered list item', async (t) => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), 'putnam-generated-list-math-'));
  const browser = await chromium.launch({ headless: true });
  try {
    const manifests = new Map();
    for (const year of [2024, 2025]) {
      manifests.set(year, await buildYear(join(sourceRoot, String(year)), join(temporaryRoot, String(year)), {
        year, contentVersion: `${year}.review`,
      }));
    }
    const page = await browser.newPage();
    for (const [year, problemId, expectedItems] of repairedHintLists) {
      await t.test(`${year}/${problemId} hint 4`, async () => {
        const problem = manifests.get(year).problems.find((entry) => entry.problemId === problemId);
        const hint = problem.hints[3];
        assert.equal(hint.stage, 'outline');
        const path = join(temporaryRoot, String(year), ...hint.assetPath.split('/'));
        await page.goto(pathToFileURL(path).href);
        assert.equal(await page.locator('.document > ol').count(), 1);
        assert.equal(await page.locator('.document > ul').count(), 0);
        const items = await page.locator('.document > ol > li').evaluateAll((elements) =>
          elements.map((item) => ({
            roots: item.querySelectorAll('.katex').length,
            formulas: [...item.querySelectorAll('.katex annotation')].map((node) => node.textContent),
          })));
        assert.deepEqual(items, expectedItems.map((formulas) => ({ roots: formulas.length, formulas })));
        assert.equal(await page.locator('.document .katex').count(), expectedItems.flat().length);
        const outsideAnnotations = await page.locator('.document').evaluate((element) => {
          const copy = element.cloneNode(true);
          copy.querySelectorAll('annotation').forEach((node) => node.remove());
          return copy.textContent;
        });
        assert.doesNotMatch(outsideAnnotations, /c_\{n,k\}|A_iA_j=A_jA_i/);
      });
    }
  } finally {
    await browser.close();
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

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

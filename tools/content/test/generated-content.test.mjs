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

// Hand-checked formulas and surrounding prose for every repaired fourth-hint item.
// Comparing prose after removing whole KaTeX roots catches any raw duplicate,
// including single symbols (S, R, G, n) without matching letters inside prose words.
const repairedHintLists = [
  [2024, 'A3', [
    { formulas: ['S', String.raw`3\times n`], prose: 'Interpret  as the standard tableaux of a  rectangle.' },
    { formulas: ['T(2,1)=2'], prose: 'Show the chosen comparison occurs exactly when .' },
    { formulas: ['f(n,n-1,n-1)/f(n,n,n)'], prose: 'Express its probability as .' },
    { formulas: ['n=2024'], prose: 'Simplify the ratio and check the bounds when .' },
  ]],
  [2025, 'A4', [
    { formulas: ['k=1'], prose: 'Rule out  because all scalars commute.' },
    { formulas: ['k=2'], prose: 'Rule out  by the Jordan-form commutant argument.' },
    { formulas: [String.raw`\mathbb R^3`], prose: 'Choose 2025 unit vectors in  orthogonal exactly for consecutive indices on the cycle and with no unwanted parallel pairs.' },
    { formulas: ['A_iA_j=A_jA_i'], prose: 'Verify directly that  exactly for the required index differences.' },
  ]],
  [2025, 'A5', [
    { formulas: ['(-1,-1)'], prose: 'Normalize the first equal pair to  using value complementation.' },
    { formulas: [], prose: 'Define the new entry by its rank among the remaining tail values and reverse the later ranks.' },
    { formulas: [], prose: 'Check the new central inequality and recover the original permutation, so the map is injective.' },
    { formulas: [], prose: 'Exhibit a pattern in the target that is not hit; iterate until all signs alternate.' },
  ]],
  [2025, 'A6', [
    { formulas: ['c_{n,k}', '2^{k+1}', '2^{k+2}'], prose: 'Show  is congruent to  modulo .' },
    { formulas: ['d_{n,k}', '2^{2k+2}', '2^{2k+3}'], prose: 'Use the same difference identity to show  is congruent to  modulo .' },
    { formulas: ['n=0'], prose: 'Apply the second lemma at .' },
  ]],
  [2025, 'B1', [
    { formulas: ['R', 'R'], prose: 'A circle centered at  contains at most two green points, so three -circles contain at most six green intersections.' },
    { formulas: ['G', 'G'], prose: 'A circle centered at  contains at most two red points, so three -circles contain at most six red intersections.' },
    { formulas: ['6+6<18'], prose: 'The 18 distinct intersections must all be red or green, but .' },
  ]],
  [2025, 'B2', [
    { formulas: ['x_2-x_1'], prose: 'Express the numerator of  as one half of a double integral.' },
    { formulas: ['x-y', 'f(x)-f(y)'], prose: 'The factors  and  have the same sign.' },
    { formulas: [], prose: 'Strict increase makes the integrand positive on a region of positive area, while all weights are nonnegative.' },
  ]],
  [2025, 'B3', [
    { formulas: ['a=b=0', String.raw`k<c\le n`], prose: 'If , use the Euler exponent  directly.' },
    { formulas: ['a+b>0', 'm=(a+b)k'], prose: 'If , use the exponent .' },
    { formulas: ['m<n', 'a+b<3^a5^b', 'k<c'], prose: 'Show  from  and .' },
    { formulas: ['n', '2025^m-15^m'], prose: 'Then  divides , so the closure property finishes the induction.' },
  ]],
  [2025, 'B4', [
    { formulas: ['r', "N=N'+r", "S=N+S'"], prose: 'Let  be the number of ones, so  and .' },
    { formulas: [String.raw`r\ge n`, '(n-1)'], prose: 'Use  and the induction bound for the -matrix.' },
    { formulas: ['n(n+1)/2'], prose: 'At most  entries can be nonzero, which completes the numerical inequality.' },
  ]],
  [2025, 'B5', [
    { formulas: ['t'], prose: 'Each  forces at least one downstep among four slots.' },
    { formulas: ['p-3', 't'], prose: 'There are  choices of .' },
    { formulas: ['I(k)', 'I(k+1)', '(p-3)/4'], prose: 'Any actual step  to  occurs in at most four slots, so the number of downsteps is at least .' },
  ]],
  [2025, 'B6', [
    { formulas: ['n', String.raw`g(n)\ge n+2`, String.raw`g(g(n))\ge g(n+2)`], prose: 'For large , exponential growth gives , so .' },
    { formulas: ['n', 'n+1', 'g(n+2)', 'g(n+1)', 'g(n+1)'], prose: 'Use the inequality at  and  to bound  both above by a power of  and below exponentially in .' },
    { formulas: [], prose: 'A polynomial cannot dominate an exponential, yielding the contradiction.' },
  ]],
];

test('generated repaired fourth hints render each formula inside its ordered list item', async (t) => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), 'putnam-generated-list-math-'));
  let browser;
  try {
    browser = await chromium.launch({ headless: true });
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
          elements.map((item) => {
            const prose = item.cloneNode(true);
            prose.querySelectorAll('.katex').forEach((root) => root.remove());
            return {
              roots: item.querySelectorAll('.katex').length,
              formulas: [...item.querySelectorAll('.katex annotation')].map((node) => node.textContent),
              prose: prose.textContent,
            };
          }));
        assert.deepEqual(items, expectedItems.map(({ formulas, prose }) => ({
          roots: formulas.length, formulas, prose,
        })));
        assert.equal(await page.locator('.document .katex').count(),
          expectedItems.flatMap(({ formulas }) => formulas).length);
      });
    }
  } finally {
    try {
      await browser?.close();
    } finally {
      await rm(temporaryRoot, { recursive: true, force: true });
    }
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

import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { auditYear, CANONICAL_PROBLEMS } from '../src/audit-year.mjs';
import { loadArchiveCatalog } from '../src/archive-catalog.mjs';
import { loadLibraryCatalog } from '../src/library-catalog.mjs';
import { findRawMathInListText } from '../src/list-math-audit.mjs';

const sourceRoot = fileURLToPath(new URL('../../../content-src/', import.meta.url));
const archiveUrl = new URL('../../../content-src/archive.json', import.meta.url);
const libraryUrl = new URL('../../../content-src/library.json', import.meta.url);
const ledgerUrl = new URL('../../../content-src/batches/2020-2025-review.md', import.meta.url);
const years = [2020, 2021, 2022, 2023, 2024, 2025];
const expectedKeys = years.flatMap((year) =>
  CANONICAL_PROBLEMS.map(({ code }) => `putnam/${year}/${code}`));

test('2024 A3 fourth-hint list renders the tableaux set label as inline math', async () => {
  const problem = JSON.parse(await readFile(
    join(sourceRoot, '2024', 'problems', 'A3.json'), 'utf8',
  ));
  const runs = problem.hints[3].document.blocks[0].items[0];
  assert.deepEqual(runs.filter(({ type }) => type === 'inlineMath').map(({ tex }) => tex),
    ['S', '3\\times n']);
});

test('all 72 Putnam 2020-2025 records keep raw math out of every document list', async () => {
  const findings = [];
  let problemCount = 0;
  for (const year of years) {
    for (const { code } of CANONICAL_PROBLEMS) {
      const problem = JSON.parse(await readFile(
        join(sourceRoot, String(year), 'problems', `${code}.json`), 'utf8',
      ));
      problemCount += 1;
      const documents = [
        ['$.statement', problem.statement],
        ...problem.hints.map(({ document }, index) => [`$.hints[${index}].document`, document]),
        ...problem.prerequisites.map(({ document }, index) =>
          [`$.prerequisites[${index}].document`, document]),
        ['$.solution', problem.solution],
      ];
      for (const [path, document] of documents) {
        findings.push(...findRawMathInListText(document, path).map((finding) => ({
          problemKey: `putnam/${year}/${code}`, ...finding,
        })));
      }
    }
  }
  assert.equal(problemCount, 72);
  assert.deepEqual(findings, []);
});

test('the completed Putnam 2020-2025 source corpus has the exact reviewed shape', async () => {
  const archive = await loadArchiveCatalog(archiveUrl);
  const library = await loadLibraryCatalog(libraryUrl);
  const putnam = library.collections.find(({ id }) => id === 'putnam');
  const validTopics = new Set(library.topics.map(({ id }) => id));

  assert.deepEqual(archive.completedYears, years);
  assert.deepEqual(putnam.sets.map(({ id }) => Number(id)).sort((a, b) => a - b), years);
  assert.equal(archive.targetYears[0], 1962);
  assert.equal(archive.targetYears.at(-1), 2025);
  assert.equal(archive.targetYears.length, 64);

  const problems = [];
  for (const year of years) {
    assert.deepEqual(await auditYear(join(sourceRoot, String(year)), year, { catalog: library }), []);
    const entries = (await readdir(join(sourceRoot, String(year), 'problems')))
      .filter((name) => name.endsWith('.json'))
      .sort();
    assert.equal(entries.length, 12, `${year}: problem count`);
    for (const entry of entries) {
      problems.push(JSON.parse(await readFile(join(sourceRoot, String(year), 'problems', entry), 'utf8')));
    }
  }

  assert.equal(problems.length, 72);
  const keys = problems.map(({ collectionId, setId, problemId }) =>
    `${collectionId}/${setId}/${problemId}`);
  assert.equal(new Set(keys).size, 72);
  assert.deepEqual([...keys].sort(), [...expectedKeys].sort());

  for (const year of years) {
    const yearProblems = problems.filter(({ setId }) => setId === String(year));
    assert.deepEqual(yearProblems.map(({ ordinal }) => ordinal).sort((a, b) => a - b),
      Array.from({ length: 12 }, (_, index) => index + 1));
  }

  assert.equal(problems.flatMap(({ hints }) => hints).length, 288);
  assert.equal(problems.flatMap(({ prerequisites }) => prerequisites).length, 219);
  assert.ok(problems.flatMap(({ prerequisites }) => prerequisites).length >= 96);
  assert.ok(problems.every(({ topics }) =>
    topics.length > 0 && topics.every((topic) => validTopics.has(topic))));
});

test('the six-year batch ledger names every canonical key and every review dimension', async () => {
  const ledger = await readFile(ledgerUrl, 'utf8');
  assert.doesNotMatch(ledger, /<development-(?:preview|review)>/);
  const rows = ledger.split(/\r?\n/).filter((line) => /^\| putnam\/20\d{2}\/[AB][1-6] \|/.test(line));
  const keys = [];

  for (const row of rows) {
    const cells = row.split('|').slice(1, -1).map((cell) => cell.trim());
    assert.equal(cells.length, 12, row);
    keys.push(cells[0]);
    assert.deepEqual(cells.slice(1, 11), Array(10).fill('complete'), cells[0]);
    const [, year, code] = cells[0].split('/');
    assert.equal(cells[11], `[review](../${year}/review/${code}.md)`);
    await readFile(join(sourceRoot, year, 'review', `${code}.md`), 'utf8');
  }

  assert.equal(rows.length, 72);
  assert.equal(new Set(keys).size, 72);
  assert.deepEqual([...keys].sort(), [...expectedKeys].sort());
  for (const year of years) {
    assert.ok(ledger.includes(
      `node tools/content/src/audit-year.mjs --year ${year} --catalog content-src/library.json`,
    ));
    assert.match(
      ledger,
      new RegExp(
        `node tools/content/src/review-year\\.mjs --year=${year} `
          + `--catalog=content-src/library\\.json [^\\n]*putnam-freeze-${year}\\\\preview[^\\n]*`
          + `putnam-freeze-${year}\\\\screenshots[^\\n]*--widths=480,720`,
      ),
    );
  }
  assert.ok(ledger.includes('npm --prefix tools/content test'));
  assert.ok(ledger.includes("python -m unittest discover -s tools/content/test -p 'test_*.py'"));
});

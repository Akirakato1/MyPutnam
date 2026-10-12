import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const validProblemUrl = new URL('../fixtures/valid-problem.json', import.meta.url);
const baseProblem = JSON.parse(await readFile(validProblemUrl, 'utf8'));

export const YEAR_CODES = [
  'A1', 'A2', 'A3', 'A4', 'A5', 'A6',
  'B1', 'B2', 'B3', 'B4', 'B5', 'B6',
];

export async function createYearFixture(t, year = 2024) {
  const fixtureRoot = await mkdtemp(join(tmpdir(), 'putnam-year-audit-'));
  t.after(() => rm(fixtureRoot, { recursive: true, force: true }));
  const sourceRoot = join(fixtureRoot, 'source');
  const originalRoot = join(sourceRoot, 'original');
  const problemsRoot = join(sourceRoot, 'problems');
  const reviewRoot = join(sourceRoot, 'review');
  await Promise.all([
    mkdir(originalRoot, { recursive: true }),
    mkdir(problemsRoot, { recursive: true }),
    mkdir(reviewRoot, { recursive: true }),
  ]);

  const problemBytes = Buffer.from('% Putnam ' + year + ' problems\n', 'utf8');
  const solutionBytes = Buffer.from('% Putnam ' + year + ' solutions\n', 'utf8');
  await Promise.all([
    writeFile(join(originalRoot, year + '.tex'), problemBytes),
    writeFile(join(originalRoot, year + 's.tex'), solutionBytes),
  ]);

  const problemUrl = 'https://example.test/' + year + '.tex';
  const solutionUrl = 'https://example.test/' + year + 's.tex';
  await writeFile(
    join(sourceRoot, 'sources.json'),
    JSON.stringify({
      year,
      publisher: 'Fixture publisher',
      usage: 'Personal, noncommercial offline study only.',
      sources: [
        sourceLock('problems', 'original/' + year + '.tex', problemUrl, problemBytes),
        sourceLock('solutions', 'original/' + year + 's.tex', solutionUrl, solutionBytes),
      ],
    }, null, 2) + '\n',
    'utf8',
  );

  for (const [index, code] of YEAR_CODES.entries()) {
    const record = structuredClone(baseProblem);
    record.collectionId = 'putnam';
    record.setId = String(year);
    record.problemId = code;
    record.label = code;
    record.ordinal = index + 1;
    record.topics = ['algebra'];
    record.statement.blocks[0].runs[0].text = 'Problem ' + code;
    record.solution.blocks.push({
      type: 'paragraph',
      runs: [{ type: 'text', text: 'This final paragraph verifies the answer and closes the proof.' }],
    });
    record.sources = [
      { kind: 'problem', label: 'Official ' + year + ' problem source', url: problemUrl },
      { kind: 'solution', label: 'Published ' + year + ' solution source', url: solutionUrl },
    ];
    await writeProblem(sourceRoot, code, record);
    await writeFile(join(reviewRoot, code + '.md'), reviewNote(year, code), 'utf8');
  }

  return { fixtureRoot, sourceRoot, problemUrl, solutionUrl };
}

export async function readProblem(sourceRoot, code) {
  return JSON.parse(await readFile(join(sourceRoot, 'problems', code + '.json'), 'utf8'));
}

export async function writeProblem(sourceRoot, code, record) {
  await writeFile(
    join(sourceRoot, 'problems', code + '.json'),
    JSON.stringify(record, null, 2) + '\n',
    'utf8',
  );
}

export function reviewNote(year, code) {
  const tick = String.fromCharCode(96);
  return '# ' + year + ' Putnam ' + code + ' content review\n\n'
    + '## Sources\n\n'
    + '- Problem source: ' + tick + 'original/' + year + '.tex' + tick + ', problem ' + code + '.\n'
    + '- Solution source: ' + tick + 'original/' + year + 's.tex' + tick + ', solution ' + code + '.\n\n'
    + '## Checks\n\n'
    + '- Statement fidelity: complete — checked against the locked problem source.\n'
    + '- Mathematical review: complete — proof route and conclusion agree with the published solution.\n'
    + '- Prerequisite review: complete — each refresher supplies reusable background rather than restating the solution.\n'
    + '- Render review: complete — all authored documents are legible at 480 CSS px and 720 CSS px.\n\n'
    + '- Topic review: complete — `algebra` is a reviewed fixture topic.\n\n'
    + '## Hint separation\n\n'
    + 'The nudge preserves the key idea; strategy names the reduction; knowledge supplies the needed fact; '
    + 'outline gives the proof route without duplicating the solution.\n';
}

function sourceLock(id, file, url, bytes) {
  return {
    id,
    title: 'Fixture ' + id,
    publisher: 'Fixture publisher',
    requestUrl: url,
    url,
    mediaType: 'application/x-tex',
    retrievedOn: '2026-10-02',
    file,
    byteLength: bytes.byteLength,
    sha256: createHash('sha256').update(bytes).digest('hex'),
  };
}

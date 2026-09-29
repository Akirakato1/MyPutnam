import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const yearRoot = new URL('../../../content-src/2025/', import.meta.url);
const expectedSources = new Map([
  ['problems', {
    file: 'original/2025-problems.pdf',
    pageCount: 2,
    url: 'https://maa.org/wp-content/uploads/2026/02/2025-Putnam-Problems-for-Sessions-A-and-B.pdf',
  }],
  ['solutions', {
    file: 'original/2025-solutions.pdf',
    pageCount: 19,
    url: 'https://maa.org/wp-content/uploads/2026/02/2025OfficialSolutions.pdf',
  }],
]);

test('the official 2025 MAA source set is complete and byte-locked', async () => {
  const manifest = JSON.parse(await readFile(new URL('sources.json', yearRoot), 'utf8'));

  assert.equal(manifest.year, 2025);
  assert.equal(manifest.publisher, 'Mathematical Association of America');
  assert.match(manifest.usage, /personal.*noncommercial/i);
  assert.deepEqual(
    manifest.sources.map(({ id }) => id).sort(),
    [...expectedSources.keys()].sort(),
  );

  for (const source of manifest.sources) {
    const expected = expectedSources.get(source.id);
    assert.ok(expected, `Unexpected source ID: ${source.id}`);
    assert.equal(source.publisher, 'Mathematical Association of America');
    assert.equal(source.file, expected.file);
    assert.equal(source.url, expected.url);
    assert.match(source.title, /2025.*Putnam/i);
    assert.match(source.retrievedOn, /^\d{4}-\d{2}-\d{2}$/);
    assert.equal(source.pageCount, expected.pageCount);
    assert.match(source.sha256, /^[a-f0-9]{64}$/);
    assert.ok(Number.isSafeInteger(source.byteLength) && source.byteLength > 0);

    const pdf = await readFile(new URL(source.file, yearRoot));
    assert.equal(pdf.length, source.byteLength, `${source.id} byte length changed`);
    assert.equal(
      createHash('sha256').update(pdf).digest('hex'),
      source.sha256,
      `${source.id} SHA-256 changed`,
    );
    assert.equal(countPdfPages(pdf), expected.pageCount, `${source.id} page count changed`);
  }
});

function countPdfPages(pdf) {
  assert.equal(pdf.subarray(0, 5).toString('ascii'), '%PDF-', 'Source is not a PDF');
  return [...pdf.toString('latin1').matchAll(/\/Type\s*\/Page\b/g)].length;
}

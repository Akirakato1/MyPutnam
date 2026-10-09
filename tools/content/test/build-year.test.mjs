import assert from 'node:assert/strict';
import { access, mkdtemp, readFile, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { buildYear } from '../src/build-year.mjs';

const sourceRoot = fileURLToPath(new URL('../../../content-src/2025/', import.meta.url));

test('build manifest records the requested immutable content version', async () => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), 'putnam-content-version-'));
  const outputRoot = join(temporaryRoot, 'output');

  try {
    const manifest = await buildYear(sourceRoot, outputRoot, {
      year: 2025,
      problems: ['A1'],
      contentVersion: '2025-upgrade-baseline',
    });

    assert.equal(manifest.contentVersion, '2025-upgrade-baseline');
    assert.equal(manifest.schemaVersion, 3);
    assert.equal(manifest.collectionId, 'putnam');
    assert.equal(manifest.setId, '2025');
    assert.deepEqual(manifest.problems[0].hints.map(({ stage }) => stage), [
      'nudge',
      'strategy',
      'knowledge',
      'outline',
    ]);
    assert.equal('originalPdf' in manifest.problems[0], false);
    assert.equal(manifest.assets.some(({ path }) => path.endsWith('.pdf')), false);
    await assert.rejects(access(join(outputRoot, 'original')));

    for (const asset of manifest.assets) {
      const bytes = await readFile(join(outputRoot, ...asset.path.split('/')));
      assert.equal(createHash('sha256').update(bytes).digest('hex'), asset.sha256, asset.path);
    }

    const katexCss = await readFile(join(outputRoot, 'content', 'shared', 'katex', 'katex.min.css'), 'utf8');
    const fontUrls = [...katexCss.matchAll(/url\((?:'|")?([^)'"?]+)(?:'|")?\)/g)]
      .map((match) => match[1]);
    assert.ok(fontUrls.length > 0, 'KaTeX CSS must reference bundled fonts');
    for (const fontUrl of fontUrls) {
      await access(join(outputRoot, 'content', 'shared', 'katex', ...fontUrl.split('/')));
    }
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
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
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

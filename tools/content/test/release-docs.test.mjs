import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const readmeUrl = new URL('../../../README.md', import.meta.url);

test('release instructions describe the current private v0.5 package-backed build', async () => {
  const readme = await readFile(readmeUrl, 'utf8');

  for (const expected of [
    '0.5.0',
    'code `5`',
    'myputnam-v0.5.0-development.json',
    'dist/myputnam-v0.5.0.apk',
    'INTERNET',
    'ACCESS_NETWORK_STATE',
    '169 immutable packages',
    '97 math-reference articles',
    'unresolved Putnam redistribution rights',
  ]) {
    assert.match(readme, new RegExp(expected.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }

  assert.doesNotMatch(readme, /declares no Internet permission/i);
  assert.doesNotMatch(readme, /continues to use its bundled offline database/i);
  assert.doesNotMatch(readme, /checked-in release is version `0\.4\.0`/i);
});

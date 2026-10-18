import { posix } from 'node:path';

import { isSafeLocalPath } from './content-types.mjs';
import { PACKAGE_LIMITS } from './package-contract.mjs';

const MEDIA_BY_EXTENSION = Object.freeze({
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
});

export function auditPackageAsset({ path, mediaType, bytes }) {
  const errors = [];
  if (!isSafeLocalPath(path) || !path.startsWith('assets/')) errors.push('$.path: asset path must be normalized beneath assets/');
  const extension = typeof path === 'string' ? posix.extname(path).toLocaleLowerCase('en-US') : '';
  const expectedMediaType = MEDIA_BY_EXTENSION[extension];
  if (!expectedMediaType) errors.push(`$.path: unsupported asset extension ${extension || '(none)'}`);
  if (expectedMediaType && mediaType !== expectedMediaType) {
    errors.push(`$.mediaType: ${mediaType} does not match ${extension} extension`);
  }
  if (!(bytes instanceof Uint8Array)) return uniqueSorted([...errors, '$.bytes: expected byte array']);
  if (bytes.byteLength > PACKAGE_LIMITS.maxEntryBytes) errors.push('$.bytes: asset exceeds maximum entry bytes');

  if (expectedMediaType === mediaType) {
    if (mediaType === 'image/svg+xml') errors.push(...auditSvg(bytes));
    else if (!hasRasterSignature(mediaType, bytes)) errors.push(`$.bytes: invalid ${mediaType} signature`);
  }
  return uniqueSorted(errors);
}

function auditSvg(bytes) {
  let source;
  try {
    source = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return ['$.bytes: SVG must be valid UTF-8'];
  }
  const errors = [];
  if (!/^\s*<svg\b/i.test(source) || !/<\/svg>\s*$/i.test(source)) errors.push('$.bytes: SVG must have one explicit svg root');
  if (/<!DOCTYPE|<!ENTITY/i.test(source)) errors.push('$.bytes: SVG declarations and entities are forbidden');
  if (/<\/?(?:script|style|foreignObject|iframe|frame|object|embed|audio|video)\b/i.test(source)) {
    errors.push('$.bytes: SVG contains a forbidden active element');
  }
  if (/\son[a-z][a-z\d:_-]*\s*=/i.test(source)) errors.push('$.bytes: SVG event handlers are forbidden');
  if (/\sstyle\s*=/i.test(source)) errors.push('$.bytes: SVG style attributes are forbidden');
  if (/&#(?:x[0-9a-f]+|[0-9]+);/i.test(source)) errors.push('$.bytes: encoded SVG character references are forbidden');

  for (const match of source.matchAll(/\s(?:href|xlink:href|src)\s*=\s*(["'])(.*?)\1/gi)) {
    if (!/^#[A-Za-z_][A-Za-z0-9_.:-]*$/.test(match[2])) errors.push('$.bytes: SVG contains a nonlocal reference');
  }
  for (const match of source.matchAll(/url\(\s*(["']?)(.*?)\1\s*\)/gi)) {
    if (!/^#[A-Za-z_][A-Za-z0-9_.:-]*$/.test(match[2])) errors.push('$.bytes: SVG contains a nonlocal paint URL');
  }
  for (const match of source.matchAll(/\sid\s*=\s*(["'])(.*?)\1/gi)) {
    if (!/^[A-Za-z_][A-Za-z0-9_.:-]*$/.test(match[2])) errors.push(`$.bytes: unsafe SVG id ${match[2]}`);
  }

  const withoutNamespace = source.replace(/\sxmlns(?::[A-Za-z_][A-Za-z0-9_.-]*)?\s*=\s*(["'])https?:\/\/www\.w3\.org\/[^"']*\1/gi, '');
  if (/(?:https?:|\/\/|data:|javascript:|file:)/i.test(withoutNamespace)) errors.push('$.bytes: SVG contains an external or executable URL');
  return errors;
}

function hasRasterSignature(mediaType, bytes) {
  if (mediaType === 'image/png') {
    const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
    return signature.every((value, index) => bytes[index] === value);
  }
  if (mediaType === 'image/jpeg') return bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  if (mediaType === 'image/webp') {
    return ascii(bytes, 0, 4) === 'RIFF' && ascii(bytes, 8, 12) === 'WEBP';
  }
  return false;
}

function ascii(bytes, start, end) {
  return String.fromCharCode(...bytes.slice(start, end));
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

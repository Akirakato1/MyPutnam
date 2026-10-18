import { isPlainObject, isSafeLocalPath, parseProblemKey, parseReferenceKey } from './content-types.mjs';
import { formatSchemaErrors, getSchemaValidator, SCHEMA_IDS } from './schema-registry.mjs';

export const PACKAGE_SCHEMA_VERSION = 1;
export const PACKAGE_LIMITS = Object.freeze({
  maxCompressedBytes: 16 * 1024 * 1024,
  maxUncompressedBytes: 64 * 1024 * 1024,
  maxEntries: 256,
  maxEntryBytes: 16 * 1024 * 1024,
});

const validateEnvelopeSchema = getSchemaValidator(SCHEMA_IDS.packageEnvelope);
const validateDescriptorSchema = getSchemaValidator(`${SCHEMA_IDS.packageEnvelope}#/$defs/packageDescriptor`);

export function validatePackageMemberPath(path) {
  return isSafeLocalPath(path)
    && path !== 'package.json'
    && !path.includes('?')
    && !path.includes('#');
}

export function validatePackageEnvelope(value) {
  const errors = [];
  if (!validateEnvelopeSchema(value)) errors.push(...formatSchemaErrors(validateEnvelopeSchema.errors));
  if (!isPlainObject(value)) return uniqueSorted(errors);

  validateCompatibility(value, '$', errors);
  validateContentKey(value.contentType, value.contentKey, '$.contentKey', errors);
  const paths = new Set();
  let totalBytes = 0;
  for (const [index, member] of (value.members ?? []).entries()) {
    const path = `$.members[${index}]`;
    if (!validatePackageMemberPath(member?.path)) errors.push(`${path}.path: unsafe package member path`);
    const folded = typeof member?.path === 'string' ? member.path.toLocaleLowerCase('en-US') : member?.path;
    if (paths.has(folded)) errors.push(`${path}.path: duplicate package member path ${member?.path}`);
    paths.add(folded);
    if (Number.isInteger(member?.bytes)) {
      totalBytes += member.bytes;
      if (member.bytes > PACKAGE_LIMITS.maxEntryBytes) errors.push(`${path}.bytes: exceeds maximum entry bytes`);
    }
  }
  if ((value.members?.length ?? 0) > PACKAGE_LIMITS.maxEntries) errors.push('$.members: exceeds maximum entry count');
  if (totalBytes > PACKAGE_LIMITS.maxUncompressedBytes) errors.push('$.declaredUncompressedBytes: exceeds maximum uncompressed bytes');
  if (Number.isInteger(value.declaredUncompressedBytes) && value.declaredUncompressedBytes !== totalBytes) {
    errors.push(`$.declaredUncompressedBytes: declared ${value.declaredUncompressedBytes} but members total ${totalBytes}`);
  }
  return uniqueSorted(errors);
}

export function validatePackageDescriptor(value) {
  const errors = [];
  if (!validateDescriptorSchema(value)) errors.push(...formatSchemaErrors(validateDescriptorSchema.errors));
  if (!isPlainObject(value)) return uniqueSorted(errors);
  validateCompatibility(value, '$', errors);
  if (!isSafeLocalPath(value.path) || value.path.includes('?') || value.path.includes('#')) {
    errors.push('$.path: package path must be a relative normalized path without query or fragment');
  }
  if (value.compressedBytes > PACKAGE_LIMITS.maxCompressedBytes) errors.push('$.compressedBytes: exceeds maximum compressed bytes');
  if (value.uncompressedBytes > PACKAGE_LIMITS.maxUncompressedBytes) errors.push('$.uncompressedBytes: exceeds maximum uncompressed bytes');
  return uniqueSorted(errors);
}

export function validatePackagePair(envelope, descriptor) {
  const errors = [];
  for (const field of [
    'contentType', 'packageRevision', 'packageSchemaVersion', 'contentSchemaVersion',
    'richDocumentVersion', 'minAppVersionCode',
  ]) {
    if (envelope?.[field] !== descriptor?.[field]) errors.push(`$.${field}: descriptor does not match package envelope`);
  }
  if (envelope?.declaredUncompressedBytes !== descriptor?.uncompressedBytes) {
    errors.push('$.uncompressedBytes: uncompressedBytes does not match package envelope');
  }
  return uniqueSorted(errors);
}

function validateCompatibility(value, path, errors) {
  const supported = (value.contentType === 'problem' && value.contentSchemaVersion === 3 && value.richDocumentVersion === 1)
    || (value.contentType === 'reference' && value.contentSchemaVersion === 1 && value.richDocumentVersion === 2);
  if (!supported) errors.push(`${path}: unsupported schema combination for ${value.contentType}`);
}

function validateContentKey(contentType, key, path, errors) {
  try {
    if (contentType === 'problem') parseProblemKey(key);
    else if (contentType === 'reference') parseReferenceKey(key);
    else errors.push(`${path}: unsupported content type ${contentType}`);
  } catch { errors.push(`${path}: invalid canonical ${contentType} key ${key}`); }
}

function uniqueSorted(errors) {
  return [...new Set(errors)].sort((left, right) => left.localeCompare(right));
}

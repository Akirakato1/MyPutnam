import { createHash } from 'node:crypto';
import { crc32 } from 'node:zlib';

import { ZipFile } from 'yazl';
import { fromBufferPromise } from 'yauzl';

import {
  PACKAGE_LIMITS,
  PACKAGE_SCHEMA_VERSION,
  validatePackageDescriptor,
  validatePackageEnvelope,
  validatePackageMemberPath,
  validatePackagePair,
} from './package-contract.mjs';
import { auditPackageAsset, auditPackageFragment } from './safe-package-asset.mjs';

const FIXED_MTIME = new Date('1980-01-01T00:00:00.000Z');
const FILE_MODE = 0o100644;

export async function createPackageArchive({
  contentType,
  contentKey,
  sourceSchemaVersion,
  richDocumentVersion,
  minAppVersionCode,
  members,
}) {
  if (!Array.isArray(members) || members.length === 0) throw new TypeError('Package members must be a non-empty array');
  const sortedMembers = members.map(normalizeMember).sort((left, right) => left.path.localeCompare(right.path));
  assertUniquePaths(sortedMembers.map(({ path }) => path));
  if (sortedMembers.length > PACKAGE_LIMITS.maxEntries - 1) throw new Error('Package exceeds maximum entry count');

  let declaredUncompressedBytes = 0;
  const declarations = sortedMembers.map((member) => {
    declaredUncompressedBytes += member.bytes.byteLength;
    if (member.bytes.byteLength > PACKAGE_LIMITS.maxEntryBytes) throw new Error(`${member.path}: exceeds maximum entry bytes`);
    assertMemberMedia(member);
    return {
      path: member.path,
      mediaType: member.mediaType,
      bytes: member.bytes.byteLength,
      sha256: sha256(member.bytes),
    };
  });
  if (declaredUncompressedBytes > PACKAGE_LIMITS.maxUncompressedBytes) throw new Error('Package exceeds maximum uncompressed bytes');

  const envelopeBase = {
    packageSchemaVersion: PACKAGE_SCHEMA_VERSION,
    contentType,
    contentKey,
    contentSchemaVersion: sourceSchemaVersion,
    richDocumentVersion,
    minAppVersionCode,
    declaredUncompressedBytes,
    members: declarations,
  };
  const envelope = {
    ...envelopeBase,
    packageRevision: sha256(Buffer.from(canonicalJson(envelopeBase), 'utf8')),
  };
  const envelopeErrors = validatePackageEnvelope(envelope);
  if (envelopeErrors.length) throw new Error(`Invalid package envelope:\n${formatErrors(envelopeErrors)}`);

  const packageBytes = Buffer.from(`${canonicalJson(envelope)}\n`, 'utf8');
  if (declaredUncompressedBytes + packageBytes.byteLength > PACKAGE_LIMITS.maxUncompressedBytes) {
    throw new Error('Package including its envelope exceeds maximum uncompressed bytes');
  }
  const archiveMembers = [
    ...sortedMembers,
    { path: 'package.json', mediaType: 'application/json', bytes: packageBytes },
  ].sort((left, right) => left.path.localeCompare(right.path));
  const bytes = await writeZip(archiveMembers);
  if (bytes.byteLength > PACKAGE_LIMITS.maxCompressedBytes) throw new Error('Package exceeds maximum compressed bytes');

  const verified = await verifyPackageArchive(bytes, { expectedDescriptor: null });
  if (canonicalJson(verified.envelope) !== canonicalJson(envelope)) throw new Error('Created package failed envelope round trip');
  return { bytes: new Uint8Array(bytes), envelope };
}

export async function verifyPackageArchive(bytes, { expectedDescriptor = null, limits = PACKAGE_LIMITS } = {}) {
  if (!(bytes instanceof Uint8Array)) throw new TypeError('Package archive must be a byte array');
  validateLimits(limits);
  const archiveBytes = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (archiveBytes.byteLength > limits.maxCompressedBytes) throw new Error('Package exceeds maximum compressed bytes');
  if (expectedDescriptor !== null) {
    const descriptorErrors = validatePackageDescriptor(expectedDescriptor);
    if (descriptorErrors.length) throw new Error(`Invalid expected descriptor:\n${formatErrors(descriptorErrors)}`);
    if (expectedDescriptor.compressedBytes !== archiveBytes.byteLength) throw new Error('Descriptor compressedBytes does not match archive');
    if (expectedDescriptor.sha256 !== sha256(archiveBytes)) throw new Error('Descriptor sha256 does not match archive');
  }

  let zip;
  try {
    zip = await fromBufferPromise(archiveBytes, {
      lazyEntries: true, decodeStrings: true, validateEntrySizes: true, strictFileNames: true,
    });
  } catch (error) {
    throw new Error(`Invalid ZIP central directory: ${error.message}`, { cause: error });
  }
  try {
    if (zip.entryCount > limits.maxEntries) throw new Error('Package exceeds maximum entry count');
    const entries = [];
    const seen = new Set();
    let extractedBytes = 0;
    for await (const entry of zip.eachEntry()) {
      validateZipEntry(entry, seen, limits);
      const payload = await readEntry(zip, entry, limits);
      extractedBytes += payload.byteLength;
      if (extractedBytes > limits.maxUncompressedBytes) throw new Error('Package exceeds maximum uncompressed bytes');
      if ((crc32(payload) >>> 0) !== (entry.crc32 >>> 0)) throw new Error(`${entry.fileName}: CRC integrity check failed`);
      entries.push({ entry, payload });
    }
    const names = entries.map(({ entry }) => entry.fileName);
    const sortedNames = [...names].sort((left, right) => left.localeCompare(right));
    if (names.some((name, index) => name !== sortedNames[index])) throw new Error('ZIP entries are not in canonical sorted order');

    const packageEntry = entries.find(({ entry }) => entry.fileName === 'package.json');
    if (!packageEntry) throw new Error('Package is missing package.json');
    let envelope;
    try {
      const envelopeSource = new TextDecoder('utf-8', { fatal: true }).decode(packageEntry.payload);
      envelope = JSON.parse(envelopeSource);
      if (envelopeSource !== `${canonicalJson(envelope)}\n`) {
        throw new Error('package.json is not canonical JSON');
      }
    } catch (error) {
      throw new Error(`Invalid package.json: ${error.message}`, { cause: error });
    }
    const envelopeErrors = validatePackageEnvelope(envelope);
    if (envelopeErrors.length) throw new Error(`Invalid package envelope:\n${formatErrors(envelopeErrors)}`);
    const { packageRevision, ...revisionPayload } = envelope;
    const computedRevision = sha256(Buffer.from(canonicalJson(revisionPayload), 'utf8'));
    if (packageRevision !== computedRevision) throw new Error('Package revision does not match canonical envelope payload');

    const actualByPath = new Map(entries
      .filter(({ entry }) => entry.fileName !== 'package.json')
      .map(({ entry, payload }) => [entry.fileName, payload]));
    if (actualByPath.size !== envelope.members.length) throw new Error('ZIP contains undeclared or missing package members');
    for (const declaration of envelope.members) {
      const payload = actualByPath.get(declaration.path);
      if (!payload) throw new Error(`${declaration.path}: declared package member is missing`);
      if (payload.byteLength !== declaration.bytes) throw new Error(`${declaration.path}: member byte length does not match envelope`);
      if (sha256(payload) !== declaration.sha256) throw new Error(`${declaration.path}: member hash does not match envelope`);
      assertMemberMedia({ ...declaration, bytes: payload });
    }
    for (const path of actualByPath.keys()) {
      if (!envelope.members.some((member) => member.path === path)) throw new Error(`${path}: undeclared package member`);
    }
    if (expectedDescriptor !== null) {
      const pairErrors = validatePackagePair(envelope, expectedDescriptor);
      if (pairErrors.length) throw new Error(`Descriptor does not match package envelope:\n${formatErrors(pairErrors)}`);
    }
    return { envelope, members: actualByPath };
  } catch (error) {
    if (/Invalid ZIP central directory/.test(error.message)) throw error;
    throw error;
  } finally {
    zip.close();
  }
}

function normalizeMember(member) {
  if (member === null || typeof member !== 'object') throw new TypeError('Package member must be an object');
  if (!validatePackageMemberPath(member.path)) throw new TypeError(`Unsafe package member path: ${member.path}`);
  if (typeof member.mediaType !== 'string' || member.mediaType.length === 0) throw new TypeError(`${member.path}: mediaType is required`);
  if (!(member.bytes instanceof Uint8Array)) throw new TypeError(`${member.path}: bytes must be a byte array`);
  let normalizedBytes = Buffer.from(member.bytes.buffer, member.bytes.byteOffset, member.bytes.byteLength);
  if (member.mediaType === 'application/json') {
    try {
      const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(normalizedBytes));
      normalizedBytes = Buffer.from(`${canonicalJson(value)}\n`, 'utf8');
    } catch (error) {
      throw new TypeError(`${member.path}: invalid UTF-8 JSON: ${error.message}`, { cause: error });
    }
  }
  return {
    path: member.path,
    mediaType: member.mediaType,
    bytes: normalizedBytes,
  };
}

function assertUniquePaths(paths) {
  const seen = new Set();
  for (const path of paths) {
    const folded = path.toLocaleLowerCase('en-US');
    if (seen.has(folded)) throw new Error(`Duplicate or case-colliding package member path: ${path}`);
    seen.add(folded);
  }
}

function assertMemberMedia(member) {
  if (member.path.startsWith('source/') && member.path.endsWith('.json')) {
    if (member.mediaType !== 'application/json') throw new Error(`${member.path}: expected application/json`);
    return;
  }
  if (member.path.startsWith('documents/') && member.path.endsWith('.fragment.html')) {
    const errors = auditPackageFragment(member);
    if (errors.length) throw new Error(`${member.path}: unsafe package fragment:\n${formatErrors(errors)}`);
    return;
  }
  if (member.path.startsWith('assets/')) {
    const errors = auditPackageAsset(member);
    if (errors.length) throw new Error(`${member.path}: unsafe package asset:\n${formatErrors(errors)}`);
    return;
  }
  throw new Error(`${member.path}: unsupported package member path or media type`);
}

function validateZipEntry(entry, seen, limits) {
  const path = entry.fileName;
  if (path !== 'package.json' && !validatePackageMemberPath(path)) throw new Error(`${path}: unsafe ZIP entry path`);
  if (path.endsWith('/')) throw new Error(`${path}: directory entries are forbidden`);
  const folded = path.toLocaleLowerCase('en-US');
  if (seen.has(folded)) throw new Error(`${path}: duplicate or case-colliding ZIP entry`);
  seen.add(folded);
  if ((entry.generalPurposeBitFlag & 1) !== 0) throw new Error(`${path}: encrypted ZIP entries are forbidden`);
  if (![0, 8].includes(entry.compressionMethod)) throw new Error(`${path}: unsupported ZIP compression method`);
  if (entry.uncompressedSize > limits.maxEntryBytes) throw new Error(`${path}: exceeds maximum entry bytes`);
  if (entry.lastModFileDate !== 33 || entry.lastModFileTime !== 0) throw new Error(`${path}: ZIP timestamp is not canonical`);
  const platform = entry.versionMadeBy >>> 8;
  const mode = (entry.externalFileAttributes >>> 16) & 0o177777;
  if (platform !== 3 || mode !== FILE_MODE) throw new Error(`${path}: directory, symlink, or special ZIP entries are forbidden`);
}

async function readEntry(zip, entry, limits) {
  let stream;
  try { stream = await zip.openReadStreamPromise(entry); } catch (error) {
    throw new Error(`${entry.fileName}: cannot open ZIP entry: ${error.message}`, { cause: error });
  }
  const chunks = [];
  let bytes = 0;
  try {
    for await (const chunk of stream) {
      bytes += chunk.byteLength;
      if (bytes > limits.maxEntryBytes) throw new Error(`${entry.fileName}: exceeds maximum entry bytes`);
      chunks.push(chunk);
    }
  } catch (error) {
    throw new Error(`${entry.fileName}: invalid compressed payload: ${error.message}`, { cause: error });
  }
  return Buffer.concat(chunks, bytes);
}

async function writeZip(members) {
  const zip = new ZipFile();
  for (const member of members) {
    zip.addBuffer(member.bytes, member.path, {
      mtime: FIXED_MTIME,
      mode: FILE_MODE,
      compressionLevel: 9,
      forceDosTimestamp: true,
    });
  }
  zip.end({ forceZip64Format: false });
  const chunks = [];
  try {
    for await (const chunk of zip.outputStream) chunks.push(chunk);
  } catch (error) {
    throw new Error(`Cannot create ZIP archive: ${error.message}`, { cause: error });
  }
  return Buffer.concat(chunks);
}

function validateLimits(limits) {
  for (const field of ['maxCompressedBytes', 'maxUncompressedBytes', 'maxEntries', 'maxEntryBytes']) {
    if (!Number.isSafeInteger(limits?.[field]) || limits[field] < 1) throw new TypeError(`Invalid package limit: ${field}`);
  }
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function formatErrors(errors) {
  return errors.map((error) => `- ${error}`).join('\n');
}

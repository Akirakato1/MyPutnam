import { createHash } from 'node:crypto';
import {
  cp,
  mkdir,
  mkdtemp,
  open,
  readFile,
  readdir,
  rename,
  rm,
  stat,
} from 'node:fs/promises';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export async function publishReleaseAssets({
  stagingAssetRoot,
  receiptPath,
  androidAssetRoot,
  onPhase = () => {},
}) {
  const staging = resolve(stagingAssetRoot);
  const receiptFile = resolve(receiptPath);
  const destination = resolve(androidAssetRoot);
  const lockPath = `${destination}.publish.lock`;
  await mkdir(dirname(destination), { recursive: true });

  let lock;
  try {
    lock = await open(lockPath, 'wx');
  } catch (error) {
    if (error.code === 'EEXIST') throw new Error(`Another release publisher is in progress: ${lockPath}`);
    throw error;
  }

  let workRoot;
  let preserveRecovery = false;
  try {
    const receipt = JSON.parse(await readFile(receiptFile, 'utf8'));
    await verifyReceipt(staging, receipt);
    workRoot = await mkdtemp(join(dirname(destination), `.${basename(destination)}.publish-`));
    const newContent = join(workRoot, 'new-content');
    const newDatabase = join(workRoot, 'new-content.db');
    const contentBackup = join(workRoot, 'content.backup');
    const databaseBackup = join(workRoot, 'content.db.backup');
    await cp(join(staging, 'content'), newContent, { recursive: true });
    await cp(join(staging, 'databases', 'content.db'), newDatabase);
    await mkdir(destination, { recursive: true });
    await mkdir(join(destination, 'databases'), { recursive: true });
    onPhase('prepared');

    const destinationContent = join(destination, 'content');
    const destinationDatabase = join(destination, 'databases', 'content.db');
    const hadContent = await exists(destinationContent);
    const hadDatabase = await exists(destinationDatabase);
    let contentInstalled = false;
    let databaseInstalled = false;
    try {
      if (hadContent) await rename(destinationContent, contentBackup);
      if (hadDatabase) await rename(destinationDatabase, databaseBackup);
      await rename(newContent, destinationContent);
      contentInstalled = true;
      onPhase('content-replaced');
      await rename(newDatabase, destinationDatabase);
      databaseInstalled = true;
      onPhase('database-replaced');
      await rm(contentBackup, { recursive: true, force: true });
      await rm(databaseBackup, { force: true });
    } catch (error) {
      try {
        if (contentInstalled) await rm(destinationContent, { recursive: true, force: true });
        if (databaseInstalled) await rm(destinationDatabase, { force: true });
        if (hadContent && await exists(contentBackup)) await rename(contentBackup, destinationContent);
        if (hadDatabase && await exists(databaseBackup)) await rename(databaseBackup, destinationDatabase);
      } catch (rollbackError) {
        preserveRecovery = true;
        throw new AggregateError(
          [error, rollbackError],
          `Release publication failed and rollback failed; recovery files retained at ${workRoot}`,
        );
      }
      throw error;
    }
  } finally {
    if (workRoot && !preserveRecovery) await rm(workRoot, { recursive: true, force: true });
    await lock.close();
    await rm(lockPath, { force: true });
  }
}

async function verifyReceipt(staging, receipt) {
  const files = await collectFiles(staging);
  const expectedTreeHash = sha256(Buffer.from(canonicalJson(files), 'utf8'));
  if (receipt.assetTreeSha256 !== expectedTreeHash
      || canonicalJson(receipt.files) !== canonicalJson(files)) {
    throw new Error('Staged bundle changed after its receipt was created');
  }
  const release = JSON.parse(await readFile(join(staging, 'content', 'release.json'), 'utf8'));
  const catalog = JSON.parse(await readFile(join(staging, 'content', 'library.json'), 'utf8'));
  const database = files.find(({ path }) => path === 'databases/content.db');
  if (receipt.releaseId !== release.releaseId
      || receipt.expectedReleaseSha256 !== sha256(Buffer.from(canonicalJson(release), 'utf8'))
      || receipt.projectedCatalogSha256 !== sha256(Buffer.from(canonicalJson(catalog), 'utf8'))
      || receipt.databaseSha256 !== database?.sha256) {
    throw new Error('Bundle receipt hashes do not match the staged release');
  }
}

async function collectFiles(root) {
  const output = [];
  async function visit(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) await visit(path);
      else if (entry.isFile()) {
        const bytes = await readFile(path);
        output.push({
          path: relative(root, path).replaceAll('\\', '/'),
          bytes: bytes.byteLength,
          sha256: sha256(bytes),
        });
      }
    }
  }
  await visit(root);
  return output;
}

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function canonicalJson(value) {
  return JSON.stringify(canonicalize(value));
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]),
    );
  }
  return value;
}

function parseArguments(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 1) {
    const [name, inlineValue] = args[index].split('=', 2);
    const takeValue = () => inlineValue ?? args[++index];
    if (name === '--staging') options.stagingAssetRoot = takeValue();
    else if (name === '--receipt') options.receiptPath = takeValue();
    else if (name === '--destination') options.androidAssetRoot = takeValue();
    else throw new Error(`Unknown argument: ${args[index]}`);
  }
  for (const key of ['stagingAssetRoot', 'receiptPath', 'androidAssetRoot']) {
    if (!options[key]) throw new Error(`Missing required publication option: ${key}`);
  }
  return options;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  await publishReleaseAssets(options);
  process.stdout.write(`Published release assets to ${resolve(options.androidAssetRoot)}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();

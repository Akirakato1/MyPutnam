import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { auditPublication } from './audit-publication.mjs';

export const TEMPORARY_PUBLIC_ACKNOWLEDGEMENT = 'I understand this temporary feed is public';

export function resolveDeploymentAuditPolicy({
  publicationMode = '',
  acknowledgement = '',
} = {}) {
  const normalizedMode = publicationMode === '' ? 'rights-cleared' : publicationMode;
  if (normalizedMode === 'rights-cleared') {
    return {
      publicationMode: normalizedMode,
      requireClearedRights: true,
    };
  }
  if (normalizedMode === 'temporary-personal-public') {
    if (acknowledgement !== TEMPORARY_PUBLIC_ACKNOWLEDGEMENT) {
      throw new Error(
        `Temporary personal publication requires this exact acknowledgement: ${TEMPORARY_PUBLIC_ACKNOWLEDGEMENT}`,
      );
    }
    return {
      publicationMode: normalizedMode,
      requireClearedRights: false,
    };
  }
  throw new Error(`Unsupported publication mode: ${normalizedMode}`);
}

export async function main(argv, environment = process.env) {
  let root;
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === '--root') root = argv[++index];
    else throw new Error(`Unknown argument: ${argv[index]}`);
  }
  if (!root) throw new Error('Missing --root');

  const policy = resolveDeploymentAuditPolicy({
    publicationMode: environment.PUBLICATION_MODE ?? '',
    acknowledgement: environment.PUBLICATION_ACKNOWLEDGEMENT ?? '',
  });
  const errors = await auditPublication(root, {
    requireClearedRights: policy.requireClearedRights,
  });
  if (errors.length) {
    throw new Error(`Deployment publication audit failed:\n${errors.map((error) => `- ${error}`).join('\n')}`);
  }
  process.stdout.write(`Deployment publication audit passed (${policy.publicationMode})\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.stack ?? error.message}\n`);
    process.exitCode = 1;
  });
}

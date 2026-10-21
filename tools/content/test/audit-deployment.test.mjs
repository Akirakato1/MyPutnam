import test from 'node:test';
import assert from 'node:assert/strict';

import {
  resolveDeploymentAuditPolicy,
  TEMPORARY_PUBLIC_ACKNOWLEDGEMENT,
} from '../src/audit-deployment.mjs';

test('unspecified publication remains behind the cleared-rights gate', () => {
  assert.deepEqual(resolveDeploymentAuditPolicy(), {
    publicationMode: 'rights-cleared',
    requireClearedRights: true,
  });
});

test('the standard publication mode remains behind the cleared-rights gate', () => {
  assert.deepEqual(resolveDeploymentAuditPolicy({ publicationMode: 'rights-cleared' }), {
    publicationMode: 'rights-cleared',
    requireClearedRights: true,
  });
});

test('temporary personal publication requires the exact public-access acknowledgement', () => {
  assert.throws(
    () => resolveDeploymentAuditPolicy({ publicationMode: 'temporary-personal-public' }),
    new RegExp(TEMPORARY_PUBLIC_ACKNOWLEDGEMENT),
  );
  assert.throws(
    () => resolveDeploymentAuditPolicy({
      publicationMode: 'temporary-personal-public',
      acknowledgement: `${TEMPORARY_PUBLIC_ACKNOWLEDGEMENT}.`,
    }),
    new RegExp(TEMPORARY_PUBLIC_ACKNOWLEDGEMENT),
  );
});

test('the acknowledged temporary personal mode audits integrity without the rights gate', () => {
  assert.deepEqual(resolveDeploymentAuditPolicy({
    publicationMode: 'temporary-personal-public',
    acknowledgement: TEMPORARY_PUBLIC_ACKNOWLEDGEMENT,
  }), {
    publicationMode: 'temporary-personal-public',
    requireClearedRights: false,
  });
});

test('unknown publication modes cannot weaken the deployment audit', () => {
  assert.throws(
    () => resolveDeploymentAuditPolicy({ publicationMode: 'anything-else' }),
    /Unsupported publication mode: anything-else/,
  );
});

// The acceptance spec for spiculedata/saiku#2162, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2162`.
//
// #2162 is a class invariant with no REST surface yet — the issue itself notes the snapshot
// render service is wired but not consumed, so there is no endpoint to call. The contract is
// that no path through the signer can produce a reference that never expires, and that lives
// in the source: this spec asserts it there.
//
// The behavioural half of C1-C4 is `SnapshotReferenceExpiryTest` (JUnit, run by `mvn verify`),
// which this spec also requires to ship. It fails on the base commit and passes on the fix,
// which is the property the convention asks of a spec; this file only guards that the fix
// cannot be reverted or half-applied without turning red.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const snapshot = join(root, 'saiku-core', 'saiku-service', 'src');
const reference = readFileSync(join(snapshot, 'main/java/org/saiku/service/snapshot/SnapshotReference.java'), 'utf8');
const signer = readFileSync(join(snapshot, 'main/java/org/saiku/service/snapshot/SnapshotReferenceSigner.java'), 'utf8');
const test = readFileSync(join(snapshot, 'test/java/org/saiku/service/snapshot/SnapshotReferenceExpiryTest.java'), 'utf8');

const MESSAGE = 'snapshot reference expiry is required';
const NEVER = /expiresAt = Long\.MAX_VALUE;/;
const SETTLED = /private long expiresAt = 0L;/;
const PARSER_NEVER = /long expires = Long\.MAX_VALUE;/;
const PARSER_SETTLED = /long expires = 0;/;

// validate() is the gate both sign() and verify() call, so the expiry check has to sit inside it.
const validate = reference.slice(reference.indexOf('public void validate()'), reference.indexOf('public static boolean isSelfOriginRepositoryPath('));

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

claim('C1', 'the Builder still defaults expiresAt to Long.MAX_VALUE', !NEVER.test(reference));
claim('C1', 'the Builder does not default expiresAt to the unset sentinel 0L', SETTLED.test(reference));
claim('C2', 'validate() does not refuse an unset expiry', /expiresAtEpochMillis <= 0/.test(validate));
claim('C2', 'validate() does not refuse the old never-expires sentinel', /expiresAtEpochMillis == Long\.MAX_VALUE/.test(validate));
claim('C2', `validate() does not throw "${MESSAGE}"`, validate.includes(MESSAGE));
claim('C3', 'the token parser still defaults a missing expiry to Long.MAX_VALUE', !PARSER_NEVER.test(signer));
claim('C3', 'the token parser does not default a missing expiry to the unset sentinel', PARSER_SETTLED.test(signer));
claim('C4', 'the regression test that covers C1-C4 does not ship', test.includes('SnapshotReferenceExpiryTest'));
claim('C4', 'the regression test does not pin the refusal message', test.includes(MESSAGE));
claim('C4', 'the regression test does not prove an already-expired reference still reads as expired', test.includes('snapshot reference token has expired'));
claim('C4', 'the regression test does not cover a token minted before the fix', test.includes('payloadFor') && test.includes('Long.MAX_VALUE'));

if (failures.length > 0) {
  for (const failure of failures) process.stderr.write(`${failure}\n`);
  process.exit(1);
}

process.stdout.write('the expiry invariant holds in the Builder, in validate(), and in the token parser\n');

// Acceptance spec for spiculedata/saiku#2423 (follow-up to #2328 / PR #2372).
// Static check, in the style of acceptance/2328: fails on base (none of the tests or the
// log-side fence exist) and passes on the fix, so a revert turns it red. Behaviour is covered
// by the named JUnit tests.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (path) => {
  try {
    return readFileSync(join(root, path), 'utf8');
  } catch {
    return '';
  }
};
const main = 'saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ai/explain';
const test = 'saiku-core/saiku-service/src/test/java/org/saiku/service/olap/ai/explain';
const probe = read(`${main}/SqlCaptureProbe.java`);
const fence = read(`${main}/SqlLogFence.java`);
const scopeTest = read(`${test}/MondrianStatementScopeTest.java`);
const serviceTest = read(`${test}/CellExplainServiceTest.java`);
const probeTest = read(`${test}/SqlCaptureProbeTest.java`);

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

claim('C1', 'a worker under the explained statement is claimed (test)', /aWorkerUnderTheExplainedStatementsLocusIsClaimed/.test(scopeTest));
claim('C1', 'a worker under a foreign statement is rejected (test)', /aWorkerUnderAForeignStatementsLocusIsRejected/.test(scopeTest));
claim('C2', 'the concurrent explain test exists', /explain_underConcurrentForeignQueries_returnsOnlyCallersSql/.test(serviceTest));
claim('C3', 'the probe fences the existing appenders', /SqlLogFence\./.test(probe) && /class SqlLogFence/.test(fence));
claim('C3', 'a test asserts existing appenders do not see unrelated DEBUG SQL', /existingAppendersDoNotSeeDebugSqlWhileAProbeIsOpen/.test(probeTest));

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('explain SQL capture follow-ups are in place\n');

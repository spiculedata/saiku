// Acceptance spec for spiculedata/saiku#2328: the explain endpoint's SQL capture
// attached a process-global log4j2 appender that deliberately was NOT filtered
// by thread or execution, so a concurrent user's SQL (filter literals, role
// predicates, physical names) could be returned as the caller's `sql`.
//
// Fix: the probe accepts an event only when its Scope claims it for the
// explaining execution (evaluated on the logging thread), and CellExplainService
// scopes it to the Mondrian statement it runs. Behaviour is covered by
// SqlCaptureProbeTest; this command spec fails on base (none of the structures
// exist) and passes on the fix, so a revert or half-apply turns it red.

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
const dir = 'saiku-core/saiku-service/src';
const probe = read(`${dir}/main/java/org/saiku/service/olap/ai/explain/SqlCaptureProbe.java`);
const service = read(`${dir}/main/java/org/saiku/service/olap/ai/explain/CellExplainService.java`);
const scope = read(`${dir}/main/java/org/saiku/service/olap/ai/explain/MondrianStatementScope.java`);
const probeTest = read(`${dir}/test/java/org/saiku/service/olap/ai/explain/SqlCaptureProbeTest.java`);
const agents = read('AGENTS.md');

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

claim('C1', 'the appender consults the scope before accepting an event', /scope\.(owns|claims)\w*\(/.test(probe));
claim('C1', 'the probe no longer documents itself as unfiltered', !/capture is\s+not filtered by thread/.test(probe));
claim('C2', 'CellExplainService opens the probe scoped to its own statement', /MondrianStatementScope\.forStatement\(/.test(service));
claim('C2', 'the scope matches the Mondrian execution on the logging thread', /Locus\.peekOrNull\(\)/.test(scope));
claim('C3', 'a foreign-thread statement is ignored (test)', /ignoresAStatementLoggedByAnotherThread/.test(probeTest));
claim('C3', 'interleaved probes do not cross-feed (test)', /interleavedProbesDoNotCrossFeed/.test(probeTest));
claim(
  'C4',
  'AGENTS.md says log4j2 for the explain probe',
  /SQL probe|SqlCaptureProbe|probe on the Mondrian planner loggers/.test(agents) &&
    !/scoped `java\.util\.logging` probe/.test(agents),
);

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('explain SQL capture is scoped to the explaining execution\n');

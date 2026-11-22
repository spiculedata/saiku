// Acceptance spec for spiculedata/saiku#2417: model-diff.yml must run the validator from a copy
// built from the trusted base and stored outside the PR checkout, only for PRs based on
// development/main. Text-based (no YAML dependency). Exits non-zero with the broken clause.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const text = readFileSync(join(root, '.github', 'workflows', 'model-diff.yml'), 'utf8');
const code = text.split('\n').filter((l) => !/^\s*#/.test(l));
const codeText = code.join('\n');

const stepsStart = code.findIndex((l) => /^ {4}steps:\s*$/.test(l));
assert.ok(stepsStart >= 0, 'diff job has a steps: list');
const steps = [];
for (const line of code.slice(stepsStart + 1)) {
  if (/^ {6}- /.test(line)) steps.push([line]);
  else if (steps.length > 0) steps.at(-1).push(line);
}
const body = steps.map((s) => s.join('\n'));

// C1
const validatorStep = body.find((b) => /java -cp/.test(b));
assert.ok(validatorStep, 'C1: a step runs the validator with java -cp');
const cpLines = validatorStep.split('\n').filter((l) => /\bCP=|-cp\b/.test(l)).join('\n');
assert.ok(!/saiku-core\/saiku-service\/target/.test(cpLines), 'C1: classpath must not use saiku-service/target inside the checkout');
assert.ok(!/(^|[^/\w])target\/classpath\.txt/.test(cpLines), 'C1: classpath must not read target/classpath.txt inside the checkout');
assert.ok(/RUNNER_TEMP|\$TOOL|\$\{TOOL\}/.test(cpLines), 'C1: classpath must point at the tool copy under RUNNER_TEMP');
assert.ok(!/TOOL=/.test(validatorStep) || /TOOL="\$RUNNER_TEMP\//.test(validatorStep), 'C1: TOOL must live under RUNNER_TEMP');
const copyStep = body.find((b) => /RUNNER_TEMP/.test(b) && /target\/classes/.test(b) && /\bcp\b/.test(b));
assert.ok(copyStep, 'C1: a step copies the base-built target/classes to RUNNER_TEMP');

// C2
const onBlock = codeText.slice(codeText.search(/^on:/m), codeText.search(/^concurrency:/m));
const branches = onBlock.match(/^\s+branches:\s*\[([^\]]*)\]/m);
assert.ok(branches, 'C2: the pull_request trigger needs a branches: [development, main] filter');
const names = branches[1].split(',').map((s) => s.trim().replace(/["']/g, '')).sort();
assert.deepEqual(names, ['development', 'main'], 'C2: branches filter must be exactly development and main');
const settings = body.findIndex((b) => /GH_PACKAGES_TOKEN:\s*\$\{\{\s*secrets\.GH_PACKAGES_TOKEN/.test(b));
assert.ok(settings >= 0, 'a step writes the credential');
const ancestor = body.findIndex((b) => /merge-base --is-ancestor[^\n]*BASE_SHA/.test(b) && /origin\/(development|main)/.test(b));
assert.ok(ancestor >= 0 && ancestor < settings, 'C2: BASE_SHA must be checked as an ancestor of origin/development or origin/main before the credential is written');

// C3
assert.ok(/BASE_SHA:\s*\$\{\{\s*github\.event\.pull_request\.base\.sha\s*\}\}/.test(codeText), 'C3: BASE_SHA must be github.event.pull_request.base.sha');
assert.ok(!/BASE_SHA:\s*\$\{\{[^}]*head/.test(codeText), 'C3: BASE_SHA must never be bound to a head SHA');
assert.ok(!/cache:\s*maven/.test(codeText), 'C3: cache: maven must not be used');

// C4
body.forEach((b, i) => {
  const lines = b.split('\n');
  const restore = lines.findIndex((l) => /git checkout[^\n]*model-diff-pr-head/.test(l));
  if (restore < 0) return;
  assert.ok(!lines.slice(restore).some((l) => /\bmvn\b/.test(l)), `C4: step ${i} runs mvn after checking out the PR head`);
  const rm = lines.findIndex((l) => /rm -f ~\/\.m2\/settings\.xml/.test(l));
  assert.ok(rm >= 0 ? rm < restore : body.slice(0, i).some((p) => /rm -f ~\/\.m2\/settings\.xml/.test(p)),
    `C4: step ${i} checks out the PR head before settings.xml is removed`);
});

// C5
assert.ok(/not exercised/i.test(codeText), 'C5: the report must say a changed tool/workflow was "not exercised"');
assert.ok(/saiku-core\/saiku-service\/src\/main\/java\/org\/saiku\/service\/schema\/diff\//.test(validatorStep + body.join('\n')) , 'C5: the tool path is compared');

// C6
let tracked = '';
try {
  tracked = execFileSync('git', ['ls-files'], { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 });
} catch {
  tracked = '';
}
const offenders = tracked.split('\n').filter((f) => /(^|\/)target\//.test(f));
assert.deepEqual(offenders.slice(0, 5), [], 'C6: files are tracked under a target/ directory');

console.log('model-diff.yml runs the validator from a base-built copy outside the checkout');

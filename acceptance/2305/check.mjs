// Acceptance spec for spiculedata/saiku#2305: model-diff.yml must not run PR-controlled Maven code
// while GH_PACKAGES_TOKEN is available. Text-based (no YAML dependency) so it runs in the bare
// command-spec environment. Exits non-zero with the broken clause on stderr.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const text = readFileSync(join(root, '.github', 'workflows', 'model-diff.yml'), 'utf8');
const code = text.split('\n').filter((l) => !/^\s*#/.test(l));
const stepsStart = code.findIndex((l) => /^ {4}steps:\s*$/.test(l));
assert.ok(stepsStart >= 0, 'diff job has a steps: list');

const steps = [];
for (const line of code.slice(stepsStart + 1)) {
  if (/^ {6}- /.test(line)) steps.push([line]);
  else if (steps.length > 0) steps.at(-1).push(line);
}
const body = steps.map((s) => s.join('\n'));
const find = (re) => body.findIndex((b) => re.test(b));

// C1
const checkout = find(/uses: actions\/checkout@/);
assert.ok(checkout >= 0, 'a checkout step exists');
assert.match(body[checkout], /persist-credentials:\s*false/, 'C1: checkout must set persist-credentials: false');

// C2
const settings = find(/GH_PACKAGES_TOKEN:\s*\$\{\{\s*secrets\.GH_PACKAGES_TOKEN/);
assert.ok(settings >= 0, 'a step writes the credential');
const toBase = body.findIndex((b, i) => i < settings && /git checkout --detach[^\n]*BASE_SHA/.test(b));
assert.ok(toBase >= 0, 'C2: a step before the credential must `git checkout --detach "$BASE_SHA"`');

// C3
const removal = find(/rm -f ~\/\.m2\/settings\.xml/);
assert.ok(removal > settings, 'C3: settings.xml must be removed after it is written');
const backToPr = body.findIndex((b, i) => i >= removal && /git checkout --detach[^\n]*model-diff-pr-head/.test(b));
assert.ok(backToPr >= removal, 'C3: the PR commit is restored only after the credential is removed');
body.forEach((b, i) => {
  if (i > removal) assert.ok(!/\bmvn\b/.test(b), `C3: step ${i} runs mvn after the credential was removed`);
});
const mvnAfterRestore = body.some((b, i) => i > backToPr && /\bmvn\b/.test(b));
assert.ok(!mvnAfterRestore, 'C3: no Maven on PR content');
console.log('model-diff.yml keeps PR code away from the Packages credential');

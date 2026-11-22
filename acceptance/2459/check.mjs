import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Runs .github/scripts/test_ci_secret_lifecycle.py against this build's tree
// (the same unittests the `scripts` job of ci.yml runs). Red on a build
// without the gate; green when the gate passes over this tree.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const gate = join(root, '.github', 'scripts', 'test_ci_secret_lifecycle.py');

const run = spawnSync('python3', [gate], { encoding: 'utf8' });
if (run.error) {
  process.stderr.write(`could not run the CI credential-lifecycle gate (${gate}): ${run.error}\n`);
  process.exit(1);
}
if (run.stdout) process.stdout.write(run.stdout);
if (run.stderr) process.stderr.write(run.stderr);

if (run.status !== 0) {
  process.stderr.write(
    'C1/C3: the CI credential-lifecycle gate is missing from this build or found a ' +
      'pull_request job running Maven/npm lifecycle code over PR content with a package ' +
      'credential readable, outside the allow-list (see #2492 for the remediation design)\n'
  );
  process.exit(1);
}
process.stdout.write(
  'CI credential-lifecycle gate passes: no pull_request job runs Maven/npm lifecycle ' +
    'code over PR content with a package credential readable outside the allow-list; ' +
    'the allow-list links #2492 and may only shrink\n'
);

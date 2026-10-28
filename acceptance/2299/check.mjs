import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const { buildFeedback, collectInput, COMMENT_MARKER, upsertComment } =
  await import(join(here, '..', '..', '.github', 'scripts', 'ci-feedback.mjs'));

const sha = 'a'.repeat(40);
const runs = [
  { id: 10, name: 'ci', run_number: 10, event: 'pull_request', pull_requests: [{ number: 42 }], path: '.github/workflows/ci.yml', status: 'completed', conclusion: 'success' },
  { id: 11, name: 'ci', run_number: 11, event: 'pull_request', pull_requests: [{ number: 43 }], path: '.github/workflows/ci.yml', status: 'completed', conclusion: 'failure' },
  { id: 12, name: 'design-system', run_number: 8, event: 'pull_request', pull_requests: [{ number: 43 }], path: '.github/workflows/design-system.yml', status: 'in_progress', conclusion: null },
  { id: 13, name: 'nightly-compliance', run_number: 7, event: 'schedule', pull_requests: [{ number: 42 }], path: '.github/workflows/nightly-compliance.yml', status: 'completed', conclusion: 'failure' },
  { id: 14, name: 'docker', run_number: 9, event: 'push', pull_requests: [{ number: 42 }], path: '.github/workflows/docker.yml', status: 'completed', conclusion: 'failure' }
];

function apiForRuns() {
  const calls = [];
  return {
    calls,
    async paginate(path) {
      calls.push(path);
      if (path.includes('/actions/runs?head_sha=')) return runs;
      if (path.includes('/10/jobs')) return [{ id: 100, name: 'build', conclusion: 'success' }];
      if (path.includes('/jobs')) return [{ id: 101, name: 'build', conclusion: 'failure' }];
      return [];
    },
    async jobLog() {
      calls.push('jobLog');
      return null;
    }
  };
}

const scopedApi = apiForRuns();
const scoped = await collectInput({ api: scopedApi, repo: 'o/r', sha, pullRequest: 42 });
assert.deepEqual(scoped.runs.map((run) => run.id), [10], 'C1/C2: only the current PR run is selected');
assert.equal(buildFeedback(scoped).status, 'passed', 'C1: unrelated failures do not turn PR feedback red');
assert.ok(scopedApi.calls.every((path) => !/runs\/(11|12|13|14)\/(jobs|artifacts)/.test(path)), 'C1/C2: unrelated run details are not fetched');

const unmatched = await collectInput({ api: apiForRuns(), repo: 'o/r', sha, pullRequest: 44 });
assert.deepEqual(unmatched.runs, [], 'C3: no other PR run substitutes for the requested PR');
assert.equal(unmatched.pullRequest, null, 'C3: no run means no confirmed PR identity');
const commentCalls = [];
const commentApi = {
  async paginate() {
    commentCalls.push('paginate');
    return [{ id: 1, user: { login: 'github-actions[bot]' }, body: `${COMMENT_MARKER}\nold failure` }];
  },
  async send() {
    commentCalls.push('send');
  }
};
assert.equal(await upsertComment({ api: commentApi, repo: 'o/r', pr: 44, feedback: buildFeedback(unmatched) }), 'skipped', 'C3: unmatched feedback cannot clear the comment');
assert.deepEqual(commentCalls, [], 'C3: no comment API call for an unmatched PR');

const unscoped = await collectInput({ api: apiForRuns(), repo: 'o/r', sha });
assert.deepEqual(unscoped.runs.map((run) => run.id), [11, 12, 13, 14], 'C4: commit-wide collection still includes non-PR runs');

process.stdout.write('acceptance/2299: CI feedback is scoped to the current PR\n');

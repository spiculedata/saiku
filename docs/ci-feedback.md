# CI failure feedback (`ci-feedback.json`)

Ported from spiculedata/saiku-cloud (issue #1374 there, part of its CI/preview/validation epic). See
[Provenance](#provenance) for what changed here.

An automated fixer once re-dispatched six times on one PR because the only failure text it saw was a Node 20
deprecation annotation; the real error was a compile error elsewhere in the log. Workers (people and agents)
need the first **real** error, not log scraping. For every commit CI now publishes:

- a machine-readable **`ci-feedback.json`** (artifact `ci-feedback-<run id>` on the `ci feedback` workflow run,
  30 days), and
- a **sticky PR comment** with the same facts, created when the commit fails and refreshed on every later run
  (a fixed commit flips it to "Nothing to fix"; a PR that never failed never gets one).

## How it works

`.github/workflows/ci-feedback.yml` reacts (`workflow_run`, `completed`) to every PR-gating workflow: `ci`,
`docker`, `design-system`, `release-prep`, `acceptance`, and the two that are still on open PR branches
(`security-scan`, `model-diff`). Each time one finishes it re-reads **all** the runs for that commit, so the
document is the same whichever workflow finished last and a newer run simply supersedes an older one.

`.github/scripts/ci-feedback.mjs` does the work (log analysis is in `ci-feedback-log.mjs`):

1. List the newest run of each workflow for the head SHA, their jobs and artifacts.
2. For each failing job (failure, timed_out, startup_failure, action_required; cancelled does not count)
   download the log tail (last 8 MiB; at most 12 job logs per document, any further failing job is listed without
   an excerpt).
3. **First real error.** Normalise the log (the runner prefixes every line with a timestamp and colours it), find
   the step that failed (the one ending `##[error]Process completed with exit code N`), and search that step's
   output for the first failure signature. Strong signatures are tried before generic ones (`[ERROR] ...`,
   `Error: ...`); then the same over the whole log. The excerpt is the error plus the lines that continue it
   (stack, compile error location), capped at 20 lines / 2000 characters.
4. **Noise is filtered.** Never the primary error: `##[warning]`/`##[notice]` annotations, `Node.js 20 is
   deprecated ... forced to run on Node.js 24`, `setup-java v4 is deprecated`, `DeprecationWarning`, the
   exit-code trailer, docker pull chatter, svelte-check `WARNING` lines (hundreds on a clean tree) and the
   application's own INFO/WARN logging. A sample of what was dropped is kept in `filteredNoise`.
5. **Failing test ids**: Surefire/Failsafe, vitest, Playwright, and the `FAILING TEST: <id>` block that
   `flake-ledger.mjs record` prints for any runner (see [`docs/ci-flakes.md`](ci-flakes.md)).
6. **Repro**: a one-line local command per job (`REPRO_BY_JOB`, then `REPRO_BY_WORKFLOW`); the fallback is
   `gh run rerun <id> --failed`.
7. **Artifacts**: every artifact of those runs with a direct link; names that look like evidence (trace,
   screenshot, playwright, lighthouse, report, container, log, coverage, har, results) are listed in
   `evidenceArtifacts`.
8. **Preview URL**: reserved. It stays `null` until a preview-environment workflow publishes one (there is none
   in this repository yet).

The `ci` job is the rollup (the one required check) and only restates the failing cells; it is listed last and
never supplies the headline `reproCommand`.

### What the log parser recognises here

| Tool | Signature it treats as the real error |
|---|---|
| Maven compiler | `[ERROR] COMPILATION ERROR :` and the `file:[line,col]` lines that follow |
| Surefire / Failsafe | `Tests run: ... Failures: n` with `<<< FAILURE!` / `<<< ERROR!`, the per-test `[ERROR] Class.method -- Time elapsed` line and its stack |
| Spotless (Palantir Java Format) | `The following files had format violations` plus the diff Spotless prints |
| Licence-header check | `ERROR: N Java file(s) have no licence header:` and the paths |
| prettier | `[warn] Code style issues found`, with the `[warn] <file>` lines printed before it |
| ESLint | the file path line and the `line:col  error  rule` finding |
| svelte-check | `<epoch> ERROR "file" line:col "message"` (machine output on a non-TTY runner) |
| vitest | `FAIL  file > suite > test` and the `AssertionError` that follows |
| Playwright | the numbered failure list `1) [chromium] > file:line > title` |
| flake policy | `FAILING TEST: <id>` |

Every fixture in `.github/scripts/fixtures/ci-feedback/` is real tool output: three are trimmed excerpts of
failing runs of this repository's `ci` workflow (a Maven compile error, a Surefire test error, a prettier
failure) and the rest were captured from each tool run against a deliberately broken input, then wrapped in the
runner's envelope (timestamps, `##[group]Run`, the exit-code trailer). The tests also replay each fixture with
the runtime-deprecation notices injected, to prove they never become the primary error.

## Schema (version 1)

Authoritative: `.github/ci-feedback.schema.json` (JSON Schema 2020-12). `ci-feedback.test.mjs` validates every
document the builder can emit against it, so the schema and the emitter cannot drift apart.

| Field | Meaning |
|---|---|
| `schemaVersion` | `1`. Bumped only on a breaking change. |
| `generatedAt` | ISO timestamp. |
| `status` | `failed` (a job failed), `pending` (nothing failed yet, something still running), `cancelled`, `passed`. |
| `repository`, `commit.sha`, `commit.branch`, `pullRequest.number` | Identity (`pullRequest` is null for pushes). |
| `runs[]` | Newest run per workflow: `id`, `name`, `status`, `conclusion`, `url`. |
| `pendingRuns[]` | Names of runs not yet completed. |
| `failingChecks[]` | `name`, `workflow`, `conclusion`, `url`, `rollup` per failing job. |
| `failures[]` | The same jobs with `firstError {line, excerpt, scope}` (or null), `logAvailable`, `failingTestIds[]`, `reproCommand`. Capped at 25; `omittedFailures` counts the rest. |
| `failingTestIds[]` | Union of the failures' test ids (max 25). |
| `reproCommand` | Repro of the first non-rollup failure; null when nothing failed. |
| `artifacts[]`, `evidenceArtifacts[]` | Artifact links (traces, screenshots, container logs, reports). |
| `previewUrl` | Reserved; null today. |
| `feedbackUrl` | The `ci feedback` run holding this document. |
| `filteredNoise[]` | Samples of the deprecation notices that were dropped. |
| `acceptance` | Optional. Added by `acceptance-runner.mjs`; see [`docs/acceptance-specs.md`](acceptance-specs.md). |

**Version policy.** Adding an optional field does not change `schemaVersion`; removing, renaming or re-typing a
field does, and the change must update the schema, the emitter, this table and the tests in one PR. Consumers
should ignore unknown fields and check `schemaVersion`.

Reading it: `gh run list --workflow "ci feedback" --branch <branch>` then
`gh run download <id> -n ci-feedback-<triggering run id>`, or just read the sticky PR comment.

## Security

This is a `workflow_run` workflow: it runs the **default-branch** (`development`) copy of the workflow, with a
token that can comment on PRs, in response to runs a PR author controls. Rules it follows, each pinned by a test:

- Sparse checkout of `.github/scripts` from the default branch; the PR head is never checked out or executed.
- PR **artifacts are never downloaded** (attacker-supplied data). Only metadata and job logs are read, over the
  REST API, as text. The pre-signed log URL is fetched without the token.
- Values from the triggering event (branch name, SHA, PR number) reach the shell only through `env:`; the
  extractor validates `--repo`, `--sha` and `--pr` before they appear in any API path.
- Permissions: `actions: read`, `contents: read`, `pull-requests: write`. Nothing else.
- Log text, job/workflow names, artifact names and branch names are untrusted: secrets are redacted (tokens,
  JWTs, AWS keys, `password=`, URL credentials, private keys), strings are truncated, markdown is escaped (no
  `@mention` pings, links or HTML), code fences outgrow any backticks inside, and URLs must be `https:` and free
  of characters that break markdown.
- The sticky comment is found by the bot's login **and** the leading marker, so a person quoting the marker is
  never edited, and the PR's own workflow can neither hide behind the feedback workflow's name (it is excluded
  by file path) nor forge its marker (HTML comments in log text are defused).
- The comment is capped below GitHub's size limit.

## Adding a gate

`workflow_run.workflows` matches on a workflow's `name:`. Add a new PR-gating workflow's name to the list in
`ci-feedback.yml` (and a repro command to `REPRO_BY_WORKFLOW` / `REPRO_BY_JOB`). `ci-feedback.test.mjs` fails
until you do, unless the workflow is listed there as deliberately unobserved (bot tooling such as `auto-review`,
`Labeler`, `Tier Classifier`, `PR Metrics`, reports and releases). A workflow counts as gating when it has a
`pull_request` trigger or a `push` trigger on `development`. `security-scan` and `model-diff` are listed in
advance (they live on open PR branches); the test tolerates exactly those two names being absent.

## Tests

`node --test ".github/scripts/*.test.mjs"` (run by `ci / flake-policy tests`). They are hermetic: no network, no
clock dependence, no CI variables.

## Limits

- `workflow_run` uses the default-branch file, so the workflow cannot be exercised from the PR that introduces
  it; the extractor itself is covered by the unit tests and was run against fixtures.
- Runs for pull requests from forks are not commented on (GitHub gives `workflow_run` no PR link for them); the
  JSON artifact is still produced.
- Only the log tail (8 MiB) is analysed; a failure printed earlier than that is not seen.
- The `mvn verify` step captures its exit code instead of failing (the flake policy needs the test reports of a
  failing run), so the `Flake policy - Maven verdict` step is the one that fails the job. On a non-zero Maven exit
  it repeats Maven's `[ERROR]` lines (at most 120) so the compile error or Spotless diff is in the failing step.

## Provenance

Ported from `spiculedata/saiku-cloud` (`.github/scripts/ci-feedback.mjs`, `ci-feedback-log.mjs`,
`ci-feedback.schema.json`, `.github/workflows/ci-feedback.yml`, `docs/ci-feedback.md`). Differences:

- Observed workflows, repro commands, rollup detection (job `ci` in workflow `ci`) and the guard test's
  definition of "gating" are this repository's.
- Log signatures added for Spotless, the licence-header check, prettier and svelte-check; the Python/actionlint/
  coverage-floor cases of the original were dropped with the tools they described.
- `FAILING TEST:` is the contract with `flake-ledger.mjs`.
- Fixtures are this repository's own logs and tool output (the originals are private).
- The timing assertions on 300 000-line and 5 MB-line logs now rely on the test timeout instead of a wall clock.
- No preview-environment, merge-queue or validation coupling.

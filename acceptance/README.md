# `acceptance/<issue>/`: executable acceptance criteria

A green build does not prove a PR did what its issue asked. The strongest defence is acceptance criteria written
**against the issue**, runnable, and committed with the fix.

So: **a PR that closes issue N ships `acceptance/N/spec.json`.** CI (`ci / acceptance convention`, a job inside
`.github/workflows/ci.yml`) fails the PR when it claims to close an issue and has no spec for it. A PR that
changes only documentation or tests opts out with the `acceptance-waived` label, and the waiver is verified
against the files the PR changed. The gate only applies to issues numbered at or above the repository variable
`ACCEPTANCE_ENFORCE_FROM_ISSUE`; while it is unset nothing is enforced.

Write the spec **first**: commit `acceptance/N/spec.json` before the implementation. `acceptance / spec fails on base`
runs every spec a PR adds or changes against the base branch's build and against the PR's build, and fails one that
already passes on base (it proves nothing). A human may excuse a legitimate exception with the `spec-already-green` label.

**What blocks a merge.** The jobs that run the specs are called from `ci.yml`, so the required `ci` check waits
for them and reads their verdicts. `ci` is red when a spec fails, when a spec could not run (`not-run`, for
example on a fork PR, is never a pass), when a new or changed spec already passes on the base build (unless a human
applied `spec-already-green`), or when the red-on-base proof did not run for a PR that changed a spec. They run on
pull requests, and the specs themselves only when the PR changes product code, the UI, a workflow or `acceptance/`
(a docs-only PR skips the Maven build and is green in about a minute). The merge queue does not re-run them.

Full format reference, assertion list, authentication and rollout: [`docs/acceptance-specs.md`](../docs/acceptance-specs.md).

The specs are executed, and the red-on-base proof is made, by the reusable workflows in
[spiculedata/acceptance-spec](https://github.com/spiculedata/acceptance-spec), called from
`.github/workflows/ci.yml` (jobs `run-specs` and `spec-first`) at a pinned commit SHA (how to bump it:
[Bumping acceptance-spec](../docs/acceptance-specs.md#bumping-acceptance-spec)). The convention gate
(`ci / acceptance convention`) is acceptance-spec's `convention.yml`, called from the same file at the same pin with
this repository's waiver list.

## Layout

```
acceptance/
  README.md          this file
  818/spec.json      the AI Query API lists and describes the FoodMart cubes behind a login (worked example, api)
  866/spec.json      HEAD and GET /rest/saiku/info answer promptly and anonymously (worked example, api)
  2172/spec.json     the convention itself (a command spec, with check.mjs)
  <N>/spec.json      one directory per issue
```

One directory per issue, never one per PR: a spec belongs to the issue, so a later fix that regresses the same
contract runs the same file.

## The shape of a spec

```jsonc
{
  "issue": 818,                     // must match the directory name
  "title": "one line a worker can read",
  "kind": "api",                    // "api" (HTTP steps) or "ui" (a command)
  "auth": "session",                // optional: "none" (default) | "session" | "basic"
  "criteria": [                     // one entry per acceptance criterion in the issue
    { "id": "C1", "given": "...", "then": "..." }
  ],
  "steps": [                        // kind: "api"
    {
      "name": "what this step proves",
      "criterion": "C1",
      "request": { "method": "GET", "path": "/ai/cubes" },   // resolved under /rest/saiku/api
      "expect": { "status": 200, "json": { "$": { "type": "array" } } }
    }
  ]
}
```

Relative step paths resolve under `/rest/saiku/api`; a path that starts with `/rest/` (for example
`/rest/saiku/info`) is used as it is. Credentials never live in a spec: `"auth": "session"` logs in at
`POST /rest/saiku/session` with `ACCEPTANCE_USER` / `ACCEPTANCE_PASSWORD` from the environment and
sends the session cookie on the later steps; `"basic"` sends HTTP basic instead. A `ui` spec carries a `command`
(argv, never a shell string) which runs in its own directory with `ACCEPTANCE_BASE_URL` set when a base URL is
configured: a Playwright suite, or any CLI check (see `acceptance/2172`). In CI the credentials are
`ACCEPTANCE_USER` / `ACCEPTANCE_PASSWORD` (the shared runner); only the local runner below still reads the
`SAIKU_ACCEPTANCE_*` names, so do not write `{{env.SAIKU_ACCEPTANCE_USER}}` into a spec.

## Running them

```bash
# against a running launcher, e.g. ./run.sh on :8080 with the FoodMart demo (the local runner; CI uses
# acceptance-spec's copy of it, with ACCEPTANCE_USER / ACCEPTANCE_PASSWORD)
SAIKU_ACCEPTANCE_USER=admin SAIKU_ACCEPTANCE_PASSWORD=admin \
  node .github/scripts/acceptance-runner.mjs run --all --base-url http://localhost:8080 \
  --out acceptance-results.json --feedback ci-feedback.json

# just the convention (what CI gates on)
PR_BODY="Closes #818" ACCEPTANCE_ENFORCE_FROM_ISSUE=1 node <acceptance-spec>/scripts/acceptance-check.mjs
```

The runner never reports "pass" for something it could not run: no base URL, an invalid spec, missing
credentials or an unreachable host is recorded `not-run` (or `fail` when the host answered and refused) with a
reason. The local runner can fold the results into `ci-feedback.json` under the optional `acceptance` key (the CI
run no longer does).

## Rules

- Derive the criteria from the issue text. If the issue is vague, fix the issue, not the spec.
- One step, one claim. Name the step after the criterion it proves.
- No sleeps, no polling the UI, no assertions on values nobody contracted.
- A spec that fails on the base commit and passes on the PR is the point; a spec that passes on both proves
  nothing.

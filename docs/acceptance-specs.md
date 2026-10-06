# Acceptance specs: `acceptance/<issue>/`

Ported from spiculedata/saiku-cloud (its #1382) and adapted to Saiku's REST surface; see
[Provenance](#provenance). Tracked here by issue #2172.

## Why

Green CI is not the same as "this PR did what its issue asked". Unit tests build objects by hand; nothing
executes the behaviour the issue asked for. So the acceptance criteria live **with the issue, as code**, next to
the fix:

- the issue templates have an **Acceptance tests** section, so a worker writes the check before the patch;
- a PR that closes issue N **must** ship `acceptance/N/spec.json`; `ci / acceptance convention` (inside the
  required `ci` rollup) fails without it, once the rollout cutoff is set;
- `.github/scripts/acceptance-runner.mjs` executes those specs against a base URL and folds the outcome into
  `ci-feedback.json`.

## The convention

```bash
# 1. the issue: fill in "Acceptance tests" when filing it
# 2. the fix: acceptance/<issue>/spec.json, derived from those criteria
# 3. the gate: ci / acceptance convention (inside ci.yml)
# 4. the run:  acceptance / specs (acceptance.yml) against a base URL
```

One directory per **issue**, not per PR: a spec belongs to the contract, so a later change that regresses it
runs the same file. The PR body claims the issue with a GitHub closing keyword (`Closes #818`, `Fixes #818`,
`Resolves #818`); a bare `#818` or "related to #818" is not a claim.

### The waiver

A PR opts out with the `acceptance-waived` label. The label is deliberate and human-applied; nothing in CI
waives itself. The waiver is **verified**: it only holds when every changed file is documentation (`docs/`,
`*.md`, `.github/ISSUE_TEMPLATE/`) or a test (`src/test/`, `__tests__/`, `saiku-ui/e2e/`, `tests/`, `*.test.*`,
`*.spec.*`, `*.e2e.*`): a tests-only change adds no product behaviour to specify. A PR that touches anything
else (a test file beside a production change still has the production file), or has no changed files, is held as
if the label were absent, and the check says which files disqualify it. The changed files are
`git diff --name-only HEAD^1 HEAD` on the PR's merge checkout, read one path per line.

### Rollout cutoff

The gate does not apply retroactively. The repository variable `ACCEPTANCE_ENFORCE_FROM_ISSUE` is an issue
number: only issues numbered at or above it owe a spec, and a PR that closes older issues is reported as
`grandfathered`. **Unset (or not a number) means nothing is enforced**, so the gate can be merged before it is
switched on. To switch it on, set the variable to the first issue number opened after the convention was
announced.

| Path | What |
|---|---|
| `acceptance/README.md` | The convention in one page, plus the shape of a spec |
| `.github/scripts/acceptance-spec.mjs` | Every pure decision: closing keywords, validation, assertions, verdict |
| `.github/scripts/acceptance-check.mjs` | CLI for the convention gate |
| `.github/scripts/acceptance-runner.mjs` | CLI to execute specs and update ci-feedback.json |
| `.github/scripts/acceptance-{check,runner}.test.mjs` | Offline unit tests (loopback HTTP server, temp dirs) |
| `.github/workflows/acceptance.yml` | Runs the specs on a PR against `ACCEPTANCE_BASE_URL` |

## Spec format

```jsonc
{
  "issue": 818,           // must equal the directory name
  "title": "one line",    // what a worker reads first
  "kind": "api",          // "api" | "ui"
  "auth": "session",      // optional, api only: "none" (default) | "session" | "basic"
  "basePath": "/rest/saiku/api",   // optional; this is the default
  "criteria": [ { "id": "C1", "given": "...", "then": "..." } ],
  "steps": [ /* api */ ] // or "command": [ /* ui, argv */ ]
}
```

### `kind: "api"`

```jsonc
{
  "name": "the cube list names Sales",
  "criterion": "C1",                 // which criterion this step proves
  "auth": "none",                    // optional per-step override of the spec's "auth"
  "request": {
    "method": "GET",
    "path": "/ai/cubes",             // under basePath; "/rest/..." is used as-is; or an absolute URL on the same origin
    "headers": { "x-example": "{{env.SAIKU_ACCEPTANCE_USER}}" },
    "json": { "any": "request body" }
  },
  "repeat": { "count": 90, "untilStatus": 429 },   // optional
  "expect": { /* see below */ }
}
```

`repeat` drives the same request until `untilStatus` (bounded at 500 attempts) and asserts on the last response
(for example to spend a rate-limit budget).

### Authentication

Saiku's REST surface under `/rest/saiku/*` is session based. The credentials come only from the environment
(`SAIKU_ACCEPTANCE_USER`, `SAIKU_ACCEPTANCE_PASSWORD`); a spec says only *how* to use them. In CI they default to
the public demo login (`admin` / `admin`, the same pair the demo deploy smoke test uses), because the target
(`ACCEPTANCE_BASE_URL`, `https://demo.saiku.bi`) is a public instance and there is nothing to keep secret. To aim
acceptance at a private instance, set the two as repository **secrets** (they override the default and are withheld
from fork PRs); never as variables, which are not masked in logs.

| `auth` | What the runner does |
|---|---|
| `none` (default) | Sends nothing. For anonymous endpoints (`/rest/saiku/info`) and to prove an endpoint refuses an anonymous caller. |
| `session` | Once per spec, `POST /rest/saiku/session` with `username` / `password` (form encoded; the endpoint is exempt from CSRF). It keeps `JSESSIONID` and `XSRF-TOKEN`, sends the cookies on later steps, and echoes `XSRF-TOKEN` as `X-XSRF-TOKEN` on non-safe methods, as the SPA does. |
| `basic` | `Authorization: Basic ...` on each request. Stateless, so no CSRF token is needed. |

If the credentials are not in the environment the spec is `not-run` ("credentials not configured"), never a pass
and never a product failure. A login the server **refuses** fails the spec (once: the refusal is remembered so
the login rate limiter is not hammered). Redirects are never followed and cookies or credentials are only ever
sent to the base URL's origin.

### `kind: "ui"`

```jsonc
{ "command": ["npx", "playwright", "test", "--config", "playwright.config.ts"] }
```

Run as argv (**never** a shell string; a metacharacter is a validation error) in the spec's own directory, with
`SAIKU_BASE_URL` and `ACCEPTANCE_BASE_URL` set when a base URL is configured. The exit code is the verdict. A
command spec needs no base URL at all, which is how `acceptance/2172` checks CI tooling.

### `expect`

| Key | Meaning |
|---|---|
| `status` | `200` or `[200, 204]` |
| `header` | `{ "name": "Retry-After", ...assertion }`, name matched case-insensitively |
| `bodyContains` | Substring of the raw body |
| `json` | Object keyed by json path (`data[0].value`, `measures.unit sales.description`; `$` is the whole document), one assertion each |

One assertion operator per path: `equals`, `contains` (substring, or membership in an array), `matches`
(regular expression), `isNull`, `exists`, `type` (`string`/`number`/`boolean`/`object`/`array`/`null`), `length`.
Add `because` for a message that says why. A path that does not resolve fails the assertion; it is never silently
absent.

Secrets never live in a spec: write `{{env.NAME}}`. An unresolved placeholder is passed through as it is, which
makes the request wrong and the step red, rather than quietly empty.

## Verdict, and what is not a pass

`pass` / `fail` / `not-run`. **A spec that could not run is never a pass**: no base URL, an unreadable or invalid
spec, missing credentials and a UI command that could not start are all `not-run` with a reason recorded. A run
in which every spec is `not-run` has the verdict `not-run`.

## Configuration

| What | Where | Effect when unset |
|---|---|---|
| `ACCEPTANCE_ENFORCE_FROM_ISSUE` | repository **variable** | Nothing is enforced (every closing PR is reported `grandfathered`). See *Rollout cutoff*. |
| `ACCEPTANCE_BASE_URL` | repository **variable** | Specs recorded `not-run`; the run is green and says so. The **gate** does not depend on it. |
| `SAIKU_ACCEPTANCE_USER` | repository **secret** | Specs with `auth: session` or `basic` are `not-run`. |
| `SAIKU_ACCEPTANCE_PASSWORD` | repository **secret** | Same. A secret, never a variable: variables are unmasked in logs. |
| `acceptance-waived` | repository **label** | The waiver cannot be applied until the label exists. |

Use a **low-privilege** account on a disposable or demo instance. The runner executes specs written in the PR
under test, so a same-repository PR author can reach whatever that account can.

## Trust model of `acceptance.yml`

The specs are authored in the PR under test and the runner executes them, so the workflow is deliberately small:
`pull_request` (never `pull_request_target`), `contents: read`, and no secret other than the acceptance account.
GitHub passes secrets to same-repository PRs only, so a fork PR runs with none and its login-dependent specs
record `not-run`. The spec commands run with an allow-listed environment (`PATH`, `HOME`, the target URL and the
`SAIKU_ACCEPTANCE_*` / `ACCEPTANCE_*` variables), not the runner's; `GITHUB_TOKEN` is not among them. Api specs
may only address the base URL's origin (a PR-authored absolute URL to another host would carry interpolated
credentials with it). Guard tests fail if `acceptance.yml` gains `pull_request_target` or `workflow_run`, reads
the acceptance credentials from `vars.`, takes a write scope or touches `GITHUB_TOKEN`. Do not widen either
without a security review.

## The worked examples

`acceptance/866/spec.json` — `HEAD /rest/saiku/info` must answer 200 within the runner timeout (the bug was a
30-second hang followed by a connection reset) and `GET /rest/saiku/info` must be an anonymous JSON array.

`acceptance/818/spec.json` — with a session login, `GET /rest/saiku/api/ai/cubes` lists the FoodMart `Sales`
cube, `GET /rest/saiku/api/ai/schema/foodmart/FoodMart/FoodMart/Sales` returns measures that carry a
`description` and `synonyms` (the semantic annotations of #818), and an anonymous caller is refused with 401. It
assumes the default seeded FoodMart connection name `foodmart`.

Both pass against a fresh 4.8.0 launcher (`SAIKU_ALLOW_DEFAULT_ADMIN=true java -jar saiku-<version>.jar serve`
with `admin` / `admin`).

`acceptance/2172/spec.json` is the third, different kind: its subject is CI tooling, so it is a `ui`-style
**command** spec (`check.mjs`) that exercises the convention itself: a closing PR with no spec is held, the
waiver and the shipped spec pass, the runner logs in with a session against a stub, and a failed spec reads as
`status: "failed"` in `ci-feedback.json` at an unchanged `schemaVersion`.

## ci-feedback.json

`ci-feedback.json` gains an optional `acceptance` key:

```jsonc
"acceptance": {
  "verdict": "fail",
  "baseUrl": "https://acceptance.example.com",
  "specs": [ { "issue": 818, "kind": "api", "verdict": "fail", "failedChecks": ["..."] } ]
}
```

A failing acceptance verdict also sets the document's `status` to `failed` and adds a `failingChecks` /
`failures` entry with a one-line repro. `schemaVersion` stays 1: adding an optional field is not a breaking
change ([`docs/ci-feedback.md`](ci-feedback.md)), and `.github/ci-feedback.schema.json` is the authority.

## Running them locally

```bash
# the convention, exactly as CI decides it
PR_BODY="Closes #2172" PR_LABELS="" ACCEPTANCE_ENFORCE_FROM_ISSUE=1 node .github/scripts/acceptance-check.mjs

# the specs against anything with a base URL
SAIKU_ACCEPTANCE_USER=admin SAIKU_ACCEPTANCE_PASSWORD=admin \
  node .github/scripts/acceptance-runner.mjs run --all --base-url http://localhost:8080 \
  --out /tmp/acceptance-results.json --feedback /tmp/ci-feedback.json

# the tooling's own tests (also `ci / flake-policy tests`)
node --test ".github/scripts/acceptance-check.test.mjs" ".github/scripts/acceptance-runner.test.mjs"
```

## Provenance

Ported from `spiculedata/saiku-cloud` (`acceptance-spec.mjs`, `acceptance-check.mjs`, `acceptance-runner.mjs`
and tests, `docs/acceptance-specs.md`, `acceptance/README.md`, `acceptance.yml`, the `acceptance-convention`
job of its `ci.yml`). The hardening added there after review is kept: the rollout cutoff, the verified waiver,
the allow-listed spec environment, the same-origin restriction for api specs, and the guard test on
`acceptance.yml`. Differences:

- Relative paths resolve under `/rest/saiku/api`; `auth` (`session` / `basic`) and `basePath` are new, as is
  `$` for the whole JSON document.
- The credentials are `SAIKU_ACCEPTANCE_USER` / `SAIKU_ACCEPTANCE_PASSWORD`, both secrets (the original used one
  API key). Missing credentials make a spec `not-run`.
- Redirects are never followed; a refused login is remembered.
- The changed-file list is split on newlines only (a comma is legal in a file name).
- The waiver's "tests" now include `saiku-ui/e2e/`.
- The convention job runs for pull requests only (a push has no PR body) and is inside `ci.yml`'s rollup.
- No preview/validation coupling.

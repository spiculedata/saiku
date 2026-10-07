# CI flakes: retry, ledger, quarantine, and the timing rule

How this repository handles a test that fails on a shared runner, and what everybody (humans and agents) is
expected to do about it. Ported from spiculedata/saiku-cloud (its #1375); see [Provenance](#provenance).

The problem this solves: a check that retries without limit hides flakes (Playwright used to run with
`retries: 2`, so a flaky e2e was silently green), and a check that is permanently red trains everyone to ignore
it. A check that is always red is not a gate, it is decoration. The fix is not to loosen everything; it is to
make flakiness **visible, attributed and expiring**.

## The rules, short

1. **One retry.** A failing test is re-run once. Pass on retry = flake, recorded and visible in the run summary;
   fail again = a real failure, gate red.
2. **Positive evidence only.** A retry counts as a pass only when the runner's own results show the test ran and
   passed on the retry (see the table below). No results, an empty retry list, or a test that was never reached
   leaves the original failure blocking.
3. **A flake is a bug with a deadline, not an excuse.** It gets fixed, or it gets quarantined with an owner and
   an expiry. There is no third option.
4. **A quarantined test still runs.** Quarantine changes whether a failure *blocks*, never whether the test
   executes. It is not deleted, not skipped, not commented out.
5. **An expired quarantine entry blocks again.** The list is checked on every run; a lapsed entry is reported
   and ignored, not honoured forever.
6. **A step that fails for a non-test reason keeps blocking.** A compile error, Spotless, dependency resolution,
   a timeout or a lost runner is never swallowed by the retry machinery.
7. **A wall-clock assertion either scales on CI or does not gate.** See [Timing assertions](#timing-assertions-the-rule).

## What runs where

| Piece | Where | What it does |
|---|---|---|
| Maven verdict | `ci.yml`, job `build`, steps `mvn verify` and `Flake policy - Maven verdict` | Surefire/Failsafe re-run a failing test once in-process; the verdict reads the reports of every module |
| vitest verdict | `ci.yml`, job `ui` | Attempt 1, then one re-run of only the failing **files**; the verdict compares the two |
| Playwright verdict | `ci.yml`, job `ui` | `retries: 1` in `saiku-ui/playwright.config.ts`; the verdict reads the JSON report |
| The logic | `.github/scripts/flake-ledger.mjs` | Parses the results, classifies, records, aggregates |
| Its tests | `.github/scripts/flake-ledger.test.mjs` and the rest of `.github/scripts/*.test.mjs` | Run by the required `ci / flake-policy tests` job |
| Quarantine list | `.github/flake-quarantine.json` | Checked in; the source of truth for what does not block |
| Rolling ledger | `.github/flake-ledger.json` and `.github/workflows/flake-ledger.yml` | Weekday 05:41 UTC the workflow folds the last ~40 `ci` runs into a ledger artifact and the job summary |

Every test job uploads a `flake-records-<signal>` artifact (`flake-records-maven`, `flake-records-ui`): one JSON
record per signal with what failed, what flaked, what was quarantined and what still blocks. That artifact is the
only channel between "CI ran a test" and "the ledger knows it was flaky".

### How each runner retries, and what proves a flake

| Runner | Retry mechanism | Positive evidence of a flake | Test id |
|---|---|---|---|
| Maven Surefire + Failsafe (JUnit 4 and 5) | `-Dsurefire.rerunFailingTestsCount=1 -Dfailsafe.rerunFailingTestsCount=1`, with `-Dmaven.test.failure.ignore=true` so the whole reactor runs | the report's `<flakyFailure>` / `<flakyError>` on a testcase with no `<failure>` / `<error>` | `Class#method` (parameterized: `Class#method(Arg)[n]`) |
| Playwright | `retries: 1` (CI) | the JSON report's `status: "flaky"` | `spec file#suite > title` (file relative to `testDir`) |
| vitest | the workflow runs the failing files a second time | the id appears in the second attempt's results and is not failed there | `file#suite > test` |

Why Maven is in-process rather than "re-run the failing test ids": in a multi-module reactor the first failing
module stops the build, so the modules after it would never run their tests, and a green re-run of only the
failing tests would look like a pass. Surefire's own rerun keeps every module in one build, and its XML says
which tests flaked. `maven.test.failure.ignore` is what lets quarantine work (a quarantined failure must not stop
the reactor), so the verdict step, not Maven, decides the outcome: it fails the job when a non-quarantined test
still fails, when Maven exited non-zero for any other reason, and when no test results exist at all.

A limit of a retry-once policy that is worth saying out loud: a vitest file re-run in isolation can pass because
it no longer runs next to whatever it depended on. A test passing in isolation after failing in the full run is
itself a signal worth investigating, not just a flake to record.

## Reading a run

The job summary has a **Flake report** block per signal:

- **Flaked, passed on retry.** Green cell, named test. Look at it this week.
- **Quarantined, still running, no longer blocking.** Named owner and expiry.
- **Failing (blocking).** The real signal. Each one is also printed as `FAILING TEST: <id>`, which is what
  [`ci-feedback.json`](ci-feedback.md) reads.
- **Failed for a non-test reason.** A step failed with no parseable test failure. These keep blocking.

## The ledger

`.github/flake-ledger.json` holds one entry per test id that flaked or failed inside the window:

| field | meaning |
|---|---|
| `rate` | hits (flaked + failed + quarantined) / `runsInWindow`: "it misbehaved in N of the last M runs" |
| `flaked` / `failed` / `quarantinedRuns` | the breakdown behind the rate |
| `signals` | which jobs covered it (`maven / verify`, `ui / vitest`, `ui / e2e`) |
| `firstSeen` / `lastSeen` | how long it has been doing this |
| `owner` / `expiresAt` | copied from the quarantine list, when it is quarantined |

The rate is a rate, not a verdict. A test at 5% over 40 runs is a candidate for quarantine; a test at 80% is a
broken test that must be fixed or deleted.

Unlike the original, the scheduled workflow is **read-only**: it uploads the rebuilt ledger as the `flake-ledger`
artifact and renders it into the job summary instead of opening a PR (that needs `contents: write` and
`pull-requests: write` on a schedule). Commit it when it is useful:

```bash
gh run download <flake-ledger run id> -n flake-ledger
mv flake-ledger.json .github/flake-ledger.json
# or rebuild by hand
gh run download <ci run id> -p 'flake-records-*' -D records
node .github/scripts/flake-ledger.mjs ledger --records records --window-runs 80
node .github/scripts/flake-ledger.mjs render
```

## Quarantine: how to add an entry, and how to get out of it

```json
{
  "testId": "org.saiku.web.rest.resources.SomeResourceTest#slowOnSharedRunners",
  "owner": "tom",
  "reason": "shared-runner latency; tracked in #1234",
  "addedAt": "2026-10-05T00:00:00Z",
  "expiresAt": "2026-10-19T00:00:00Z",
  "issues": ["#1234"]
}
```

- `testId` is exactly what the run summary prints. Copy it from there; matching is exact, so `Foo#bar` does not
  excuse `Foo#barBaz`.
- `owner` and `expiresAt` are **required**. An entry without an owner, or with an expiry in the past, is
  ignored: the test blocks again. `ci / flake-policy tests` fails on an invalid or expired entry, and the ledger
  workflow lists them.
- **Default expiry: two weeks.** Long enough to fix a genuinely timing-dependent test, short enough that
  "temporary" cannot quietly become permanent.
- Removing the entry, fixing the test, or letting it expire all unblock the test. Fixing the test is the one
  that counts.

```bash
node .github/scripts/flake-ledger.mjs quarantine            # human-readable
node .github/scripts/flake-ledger.mjs quarantine --check    # non-zero if anything is invalid or expired
```

## Timing assertions: the rule

> **A wall-clock or latency assertion either scales on the shared runner or does not gate. It never gates
> unscaled.**

Shared GitHub-hosted runners are not a slower laptop, they are a different machine: consistently several times
slower and noisily so. An unscaled millisecond budget in a required gate is a coin flip, and a coin flip that
people learn to re-roll is how a required gate stops meaning anything. There are three legal shapes:

1. **Scaled on CI, full budget locally.** The production budget stays the gate for a developer; CI multiplies it
   by an explicit factor that the *workflow* sets (an `env:` entry next to every other CI knob), so the number is
   visible. A test must read that variable rather than hard-code a second factor. No test here needs one yet.
2. **Measured and reported, not gating.**
3. **Nightly or soak only**, where nobody waits on it and a red morning is not a broken afternoon.

Prefer the *fastest* of N runs to the mean: contention and JIT warm-up can only make a run slower. Do not paper
over a real regression with a retry: a retry is for the runner, not for the code. Tests of the tooling itself
(`.github/scripts/*.test.mjs`) follow the same rule: they never assert on a wall clock and never depend on
today's date (dates are injected, or computed relative to the clock they read).

## What is deliberately not here

- **No automatic quarantine.** Adding an entry is a human decision with a name on it.
- **No test skipping via the retry.** Nothing is marked skipped or `@Disabled`.
- **No flake rate below a threshold being ignored.** A low rate still shows in the ledger.
- **No second retry.** Playwright used to retry twice in CI; it now retries once.

## Provenance

Ported from `spiculedata/saiku-cloud` (`.github/scripts/flake-ledger.mjs`, its tests, `docs/ci-flakes.md`,
`flake-ledger.yml`, the flake steps of its `ci.yml`). Differences:

- Maven uses Surefire's in-process rerun plus a verdict step (the original ran one module per matrix cell and
  re-ran failing test ids with `-Dtest`); the `-Dtest` selector (`mavenTestSelector`) is kept for
  `flake-ledger.mjs select`.
- `flake-ledger.mjs` gained: multi-location `--source`, Surefire `<flakyFailure>` and Playwright JSON parsing,
  the single-pass `record --source`, `--build-failed`, `--require-results` and the `FAILING TEST:` block.
- Playwright is covered (the original lists it as a gap). Playwright config: `retries: 1`, JSON report.
- The ledger workflow is read-only.
- Test dates are relative to an injected `now` or the real clock, never a hard-coded "future" date.

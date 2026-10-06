# PR review rubric

**Status:** active · **Applies to:** every PR opened against `development`
· **Last reviewed:** 2026-01

This is the shared vocabulary Saiku reviewers and agents use to talk about a
change. It is deliberately short: a rubric that costs more to read than the diff
is not used. It is not a gate — `CONTRIBUTING.md` § *PR review* still governs
process (one maintainer approval + green CI for routine changes, more eyes for
substantive ones). This document says **what** to look for so that human and
agent reviewers reach for the same things.

Mechanical review already happens in CI. `.github/workflows/claude-code-review.yml`
runs an automated reviewer on every non-bot PR, and `ci.yml` runs `mvn verify`
(Spotless + unit/IT suites + test floors) and, for UI paths, `npm run check` +
vitest. **A green CI run discharges the mechanical axes of this rubric.** Reviewers
should spend their attention on the axes no linter can judge.

## How to use this rubric

1. Skim the diff once for shape, not detail. Identify the axes below that apply —
   most PRs only raise two or three.
2. Check each applicable axis in turn. State a finding as **axis → file:line →
   what breaks → what you'd accept instead**.
3. Separate **blocking** from **non-blocking**. Blocking means: a correctness bug,
   a security or data-loss risk, a licence/legal problem, a broken build or a
   violated convention in the list below. Everything else is advice — label it
   explicitly as a non-blocking suggestion so the author can decline it without a
   second round of review.
4. Say what is good. A rubric that only accumulates objections teaches authors to
   open fewer, smaller PRs — the opposite of what this repository wants.

## Severity labels

Use these in review comments so the author knows what must happen before merge.

| Label | Meaning |
| --- | --- |
| `blocking` | Must be fixed before merge. A bug, a security or data-loss risk, a licence problem, or a build/convention violation. |
| `non-blocking` | Genuine improvement, optional. The author may decline with a reason. |
| `question` | You do not yet know why the code does what it does. Not a defect. |
| `praise` | Worth keeping — a non-obvious fix, a well-placed test, a comment that will save the next reader an hour. |

## The axes

### 1. Correctness

*Does it do what the PR claims, including at the edges?*

- Re-read the claim in the PR body against the diff. A PR whose body describes
  work the diff does not contain is the most common blocking finding in this repo.
- Boundary cases: empty collections, null/blank input, single-element collections,
  very large inputs, unicode, concurrent access, timezone/locale, and 64-bit
  overflow.
- Language level: the reactor's compiler is pinned in the root pom, not by the
  `maven.compiler.*` properties — check which level the edited module actually
  compiles at before using newer syntax. A build failure here is `blocking`.
- Resource lifetimes: every `Statement`/`Connection`/`InputStream` closed, on the
  exception path too.
- Error handling: an exception that is caught and logged but leaves the caller
  believing success is worse than a crash. Fail closed — see the Mondrian role
  handling in `SaikuMondrianHelper` for the pattern the project expects.
- Concurrency: shared mutable state introduced without synchronisation, or a new
  field read from a request thread and written by another.

### 2. Tests

*Would this test suite fail if the change were reverted?*

- Every behaviour change ships with a test that fails before the fix and passes
  after. Match the stubbing style of the neighbouring tests in that package.
- A test that asserts the implementation rather than the observable behaviour
  locks in the wrong contract. `non-blocking` unless it is actively harmful.
- Deleting or `@Disabled`-ing a test to get green is always `blocking`. So is
  lowering a floor in `.github/test-floors.json` or `.coverage-thresholds.json` —
  raise a floor when you add tests.
- Flaky tests: timing assumptions, real sleeps, fixed ports, shared temp paths,
  order dependence. A test that fails 1 run in 20 is a merge risk — `blocking`
  until characterised and made deterministic.
- UI changes need `saiku-ui` coverage (vitest; Playwright where the interaction
  is the point). Remember `mvn verify` builds `saiku-ui` but does **not** run its
  tests.

### 3. Repo conventions

*Did the change follow the rules already written down in `AGENTS.md`?*

Read the *Conventions specific to this repo* section of `AGENTS.md` — most
convention findings are already documented there. The most-violated, in order:

- Spring **XML** bean wiring (`applicationContext-*.xml`), never `@Configuration`.
- JAX-RS **Jersey** (`jakarta.ws.rs.*`) resources mounted under `/rest/*`, never
  Spring `@RestController`. A bare `/saiku/api/...` path 403s — check the mount.
- Svelte 5 effect discipline: never call a state-writing helper synchronously
  inside `$effect` when it reads the same `$state`; `effect_update_depth_exceeded`
  is the signal and it takes the whole component's handlers down with it.
- No raw tone classes (`bg-emerald-*`, `text-red-*`, `rose`, …) outside
  `saiku-ui/src/lib/design-system/` — token utilities only.
- Imports from the `$lib/design-system` / `$lib/components/ui` barrels, never
  deep paths into the shared package.
- Dependency changes: an override in `saiku-bom` is load-bearing; changing the
  BOM alone can silently drift a pin. Check `git diff saiku-bom/pom.xml` whenever
  a version moves.
- Doc updates travel with behaviour changes. `docs/AGENT-SPACES-SPEC.md`,
  `docs/SKILLS-SPEC.md`, `docs/AI-QUERY-API.md` and friends are specs — a change
  that contradicts one updates the spec in the same PR.
- Canonical fixtures are the git-tracked ones (e.g.
  `saiku-launcher/src/main/resources/seed/FoodMart4.xml`). Editing only the
  runtime copy under `saiku-home/` changes nothing for anyone else.

### 4. Security and data

*Could this leak data, escalate privilege, or corrupt a store?*

- **Secrets and credentials** must not enter the repo, logs, or fixtures. Redact
  connection strings in test data. `blocking`.
- **AuthN/authZ** changes: new endpoints are denied by default. Verify the role
  check on every new REST resource and that failure is closed, not open.
- **Query paths** (MDX, SQL, JDBC URLs, GraphQL) take user input. Is it
  parameterised or schema-validated, or merely string-concatenated? MCP tool
  arguments and the typed MDX binder are the two layers that keep untrusted
  values out of the parser — a new one must not remove them. `blocking`.
- **Role mapping** must fail closed on blank or unknown values, per saiku#1968 /
  #1972 / #2030. A present-but-invalid role that falls through to a root role is
  `blocking`.
- **Deserialisation** of untrusted payloads and **new dependencies**: check the
  CVEs the bump actually closes (this repo has been burned by Hive/Hadoop and
  log4j 1.x arriving as mandatory WAR jars).
- **Migrations** are forward-only and reversible-in-code; destructive schema
  changes need a stated rollout order.

### 5. Compatibility and blast radius

*What breaks for someone who is not looking at this PR?*

- Public REST/GraphQL/MCP contract changes need a version note and a migration
  path, or an explicit "no external callers yet".
- JVM and runtime: main artifacts target **release 21**; only `saiku-proptest` is
  Java 22 and only for Hegel's FFM/native engine. Don't raise a module's release
  without saying why.
- `saiku-ui` ships to two products — this repo *and* saiku-cloud, which consumes
  the published `@concepttocloud/saiku-design-system` package. Any change under
  `saiku-ui/design-system/**` has an out-of-repo consumer. Bumping its `version`
  in `package.json` is what publishes it; `design-system.yml` no-ops when the
  registry already has that version.
- Generated artifacts (`saiku-ui/dist/`, `target/`, `saiku-home/`) must not be
  committed. Stale bundles in the war are a real hazard here — `mvn clean`
  `saiku-webapp` and `saiku-launcher` before rebuilding after a UI change.

### 6. Commits, branch, PR hygiene

*Is the history legible?*

- Base is `development`, branch is Gitflow (`feature/…`, `fix/…`, `chore/…`) off
  `development`. Never `main`. Never push directly to `development`.
- Commit messages follow `#<issue> - <type>(<area>): <what changed>`, per
  `CONTRIBUTING.md`, and carry a `Signed-off-by:` trailer whose email matches the
  author (the DCO check blocks the merge without it).
- One logical change per PR. A 3,000-line diff is not reviewable — say so early
  rather than skimming it.
- The PR body says what changed, why, how it was verified, and closes the issue
  with `Closes #N`.
- If a maintainer had to correct a convention, the correction belongs in
  `.claude/memory/corrections.md` in that same PR.

## Working with the automated reviewer

`.github/workflows/claude-code-review.yml` posts findings on every non-bot PR. It
is a strong first pass and it does not read `AGENTS.md` reliably, so:

- Triage its findings rather than treating them as authoritative. Confirm each
  against the code before acting or dismissing; a dismissed-by-nobody finding is
  the cheapest kind of noise to remove.
- It cannot see local state a reviewer can — a divergent `saiku-home/`, a stale
  bundle, an editor running the launcher while `mvn clean` is invoked.
- Use `.github/prompts/review.md` to run the same rubric yourself in any agent.

## For agents

Run the rubric in this order and report per axis with a severity label:

```
1. Read the PR body. Diff the claim against the actual diff.
2. axes 1 & 4 (correctness, security) — highest value, read the whole diff.
3. axis 2 — is there a test that fails without the change?
4. axis 3 — grep AGENTS.md conventions against the diff; cite the rule you applied.
5. axes 5 & 6 — compatibility, then hygiene.
6. Report blocking findings first, then non-blocking, then praise. One line each.
```

Never edit another contributor's PR. Post findings as comments; let the author
make the change.
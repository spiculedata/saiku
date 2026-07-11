# Contributing to Saiku

Thanks for wanting to help. This document covers everything from a
one-line typo fix to a whole feature — pick the depth you need.

For the human side of the project (who decides what, how commit
rights work, licensing posture), see [`GOVERNANCE.md`](./GOVERNANCE.md).

## Quickstart for contributors

The whole loop is: fork → clone → branch → change → PR.

```bash
# 1. Fork spiculedata/saiku on GitHub, then clone your fork
git clone git@github.com:YOUR_USERNAME/saiku.git
cd saiku

# 2. Track our upstream so you can pull in main-branch updates
git remote add upstream https://github.com/spiculedata/saiku.git
git fetch upstream

# 3. Branch off development, NOT main / master
git checkout -b feature/short-descriptive-name upstream/development

# 4. Build once so you know your machine is set up
mvn verify   # requires JDK 21 + Maven 3.9+

# 5. Make your change. Write tests. Run `mvn spotless:apply` before
#    committing to satisfy the style-check.

# 6. Open a PR against `development` (NOT `main`)
```

Every PR against `development` triggers CI on Ubuntu and macOS with
JDK 21. Green CI + one maintainer approval merges the PR.

## Development setup

**Prerequisites:**

- JDK 21 (Temurin recommended). Older JDKs won't compile the reactor.
- Maven 3.9+.
- Node.js 20+ (only if you're touching `saiku-ui/`).
- Docker (only for the launcher integration tests and end-to-end
  UI runs).

**One-time setup:**

```bash
./scripts/install-hooks.sh   # installs the pre-commit spotless hook
```

**Common commands:**

```bash
mvn verify                                              # full build + tests + spotless-check (CI's gate)
mvn -pl saiku-core/saiku-service -am test               # one module's tests
mvn -pl saiku-core/saiku-service test -Dtest=MyTest     # single test class
mvn -pl saiku-launcher -am -Dmaven.test.skip=true package   # build the runnable fat-JAR
mvn spotless:apply                                      # auto-format Java (Palantir style)
```

Full build environment details — including the GitHub Packages auth
gotcha for Mondrian dependencies — live in
[`CLAUDE.md`](./CLAUDE.md) at the repo root.

## Branching

We use **Gitflow**. In practice:

- All feature work goes on a `feature/<name>` branch off `development`.
- Hotfixes branch off `main` as `hotfix/<name>` and merge back to
  both `main` and `development`.
- Release prep happens on `release/<version>` branches off
  `development`, which then merge to `main` (tagged) and back to
  `development`.
- Never push directly to `main` or `development` — always PR.
- Chores and refactors that don't fit `feature/` use `chore/<name>`.

## Commit messages

Format:

```
#<issue> - <short description>

Optional longer body explaining why (not what — the diff shows the
what). Wrap at 72 columns.
```

- Reference an issue number when the change fixes one. If no issue
  exists, that's fine for small changes; open one first for anything
  substantial.
- Types we use in commit prefixes when helpful: `feat`, `fix`,
  `docs`, `chore`, `refactor`, `test`, `perf`, `ci`.
- Attribution is set up globally; don't add `Co-Authored-By` unless
  it's a genuine pair-programmed change.

## Testing expectations

We aim for **80%+ test coverage** on new code. Tests come in three
flavors:

- **Unit tests** — individual functions, DTOs, services. Fast, no
  network. Live under `src/test/java`.
- **Integration tests** (`*IT.java`) — spin up a real database (H2
  in-process), exercise the SQL adapter or query paths. Live
  alongside the code they test.
- **End-to-end tests** — the launcher IT harness in
  [`saiku-launcher/test-*-live.sh`](./saiku-launcher/) drives a
  live server over HTTP.

Bugfixes come with a regression test. New features come with
enough coverage that a future refactor can trust the tests. Ask
in the PR if you're unsure how much is enough — happy to discuss.

## Code style

- **Palantir Java Format** enforced via
  [Spotless](https://github.com/diffplug/spotless). `mvn spotless:apply`
  formats. The pre-commit hook installed by
  `scripts/install-hooks.sh` runs it automatically.
- **Spring XML wiring**, not JavaConfig. Webapp beans live in
  `applicationContext-*.xml`; new beans go there.
- **JAX-RS 3 (Jersey)**, not Spring MVC. REST resources are
  `@Path`-annotated Jersey resources.
- **Prefer immutability** at the DTO layer. New DTOs should have
  final fields where practical.

The `.spotless` config is authoritative on formatting; the human
guide is short: read the surrounding code and match it.

## Reporting bugs

1. Search [existing issues](https://github.com/spiculedata/saiku/issues)
   first — chances are it's already logged.
2. If not, open a new issue with:
   - Saiku version (`docker inspect ghcr.io/spiculedata/saiku` or
     the launcher log's first line)
   - What you expected to happen
   - What actually happened, with the log excerpt if there was one
   - Steps to reproduce that a stranger could follow
3. If it's a **security issue**, don't open a public issue — see
   [`SECURITY.md`](./SECURITY.md) for the disclosure process.

## Requesting features

Open a GitHub issue tagged `enhancement`. Explain the use case
first, the implementation second — knowing why matters more than
knowing how. If it's a big directional ask, please raise it on
[GitHub Discussions](https://github.com/spiculedata/saiku/discussions)
before committing time to a large PR.

For roadmap-level changes, see the "How to propose a change" section
of [`ROADMAP.md`](./ROADMAP.md).

## PR review

- One maintainer approval + green CI is enough to merge routine
  changes.
- Substantive changes (breaking APIs, licensing implications,
  cross-module refactors) get more eyes — expect discussion, and
  give reviewers time.
- If a review sits idle for more than a week, ping the PR politely.
  We're a small team; things fall through.

Reviews are meant to be a conversation, not a gate. If a reviewer
asks for a change you disagree with, push back with reasoning —
the review usually improves for it. If we can't reach agreement,
the primary maintainer has the final call, but that's rare.

## Code of conduct

We follow the [Contributor Covenant](./CODE_OF_CONDUCT.md).
Violations get reported to Tom Barber at
[tom@spicule.co.uk](mailto:tom@spicule.co.uk); serious ones lose
project access.

## Licensing

By opening a PR you affirm that you have the right to contribute
the code and agree to license it under Apache 2.0 + EPL 1.0 (the
Saiku licence). No CLA click-through required — a plain
`Signed-off-by:` trailer on your commit, per
[Developer Certificate of Origin 1.1](https://developercertificate.org/),
is sufficient.

Add it with `git commit -s`.

## Thank you

The project has gone through eras with different amounts of
external maintenance. Every PR from an external contributor is
what keeps it a real open-source project rather than a company's
public artifact. Thank you for spending the time.

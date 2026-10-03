# Security policy

## Reporting a vulnerability

**Do not open a public GitHub issue for security-sensitive reports.**

Email [security@saiku.bi](mailto:security@saiku.bi) with:

- A description of the vulnerability
- The Saiku version(s) affected
- Steps to reproduce, ideally with a minimal proof of concept
- Any known impact assessment

We acknowledge receipt within 48 hours (usually same day during UK
business hours) and share our first triage assessment within seven
days. If the finding is confirmed, we agree a disclosure timeline
with you — typically 30-90 days from acknowledgement, longer for
deep-cutting fixes, shorter for actively-exploited issues.

If your finding lands within scope, we're happy to credit you
publicly in the fix's release notes and CVE record. We don't
currently run a paid bug bounty program.

## What's in scope

- The current major version (Saiku 4.x) of the open-source build
- [Saiku Cloud](https://cloud.saiku.bi) production endpoints
- The AI Query API surfaces (`/rest/saiku/api/ai/*`)
- The MCP endpoint (`/rest/saiku/api/mcp`)
- The Ossie library
  ([`bi.saiku.ossie:ossie-core`](https://github.com/spiculedata/ossie),
  `bi.saiku.ossie:ossie-sql`)

Reports against the following are welcomed but a lower priority:

- Saiku 3.x — no longer maintained
- Third-party dependencies with issues fixed upstream — we bump
  those on our normal cadence
- Demo instance (`demo.saiku.bi`) findings that don't affect a
  hardened production deployment

## What's not in scope

- Social engineering against Spicule staff
- Denial-of-service against `demo.saiku.bi` (it's a demo; running
  it at rope's end doesn't tell us anything about production)
- Findings that require an attacker who already has admin
  credentials
- Missing security headers on cloud-hosted marketing pages
  (`saiku.bi`) — reports welcomed but treated as informational
- Missing rate limits on obviously-throttleable endpoints — please
  suggest a reasonable limit rather than flooding the endpoint to
  make the point

## PGP

If you need to encrypt your report, ask for our PGP key in a first
short email and we'll reply with it.

## What we do on our side

These are the controls that actually run today. If one of them is aspirational we
say so rather than listing it as a fact — a security policy that overstates its
own coverage is worse than a short one, because a reader stops checking.

- **Dependency scanning (every push and PR).** `.github/workflows/security-scan.yml`
  runs GitHub's `dependency-review-action` against the advisory database on every
  pull request, failing the PR when it *introduces* a dependency with a
  high-or-above vulnerability. The same workflow runs `gitleaks` over the full
  commit history on every push, PR, and on a weekly schedule, so a newly published
  advisory against an already-merged dependency still surfaces. Both actions are
  pinned to commit SHAs.

  What this is not: it is not a full vulnerability scan of the shipped artifact.
  It reports the *delta* a change introduces, not the standing CVE count of the
  dependency tree.

- **OWASP Dependency-Check — opt-in, not automatic.** `mvn -P security verify`
  runs the OWASP `dependency-check-maven` plugin (`check` goal) against the
  authoritative NVD feed. It is **not** part of `release.yml` and does **not** run
  on every push: the NVD download plus analysis takes roughly ten minutes, which
  is the wrong trade to impose on every commit. Run it yourself before a release
  cut, or ask us for the result of the most recent run. We do not currently
  enforce a CVSS 7.0 release block automatically.

- **Secrets scanning.** GitHub secret scanning and push protection are enabled
  repo-wide (a repository setting, not a workflow in this tree). On top of that
  the pre-commit hook installed by `./scripts/install-hooks.sh` runs
  `scripts/check-secrets.sh`, which rejects a commit whose staged content matches
  a common credential shape (AWS keys, GitHub tokens, Anthropic/OpenAI keys,
  Slack tokens, Google API keys, private-key blocks, an inline embed-JWT secret).
  That hook is a fast local pre-flight and a shape check, not a scanner: gitleaks
  in CI is the authoritative check.

- **Fail-closed defaults.** Non-obvious ones: the AI policy guard
  defaults to `SCHEMA_ONLY` in production so AI endpoints don't
  return aggregated data or raw rows without explicit operator
  opt-in; the launcher refuses to serve in production while the
  default admin password (`admin/admin`) is unchanged; share links and
  embed tokens re-resolve the owner's live roles on every read, so a
  disabled or demoted owner revokes guest access on the next request
  (saiku#1920) rather than serving the mint-time role snapshot.

- **Egress — opt-in, with named exceptions.** Observability is opt-in
  (`saiku.telemetry.*`), so no data leaves the box unless you configure
  it. Two integrations need naming so the claim isn't read as absolute:
  the launcher ships a telemetry heartbeat that is **opt-out** (it
  reports unless an operator disables it), and the demo instance runs
  analytics. Neither applies to a hardened production deployment of
  your own, and both can be turned off — see the configuration reference
  in the docs.

- **Review routing.** `.github/CODEOWNERS` routes changes to `.github/`
  (including the workflows themselves), the web security packages, the
  embed/share token stores, the launcher, and this file to the maintainers
  for review.

- **Vulnerability triage.** Findings are logged internally with a
  planned fix window; the fix + disclosure ship together per the
  agreed timeline.

Full production security posture is at
[docs.saiku.bi/security/posture](https://docs.saiku.bi/security/posture/).

## Contact

- **Security disclosures:** [security@saiku.bi](mailto:security@saiku.bi)
- **General inquiries:** [hello@saiku.bi](mailto:hello@saiku.bi)
- **Governance questions:** [tom@spicule.co.uk](mailto:tom@spicule.co.uk)

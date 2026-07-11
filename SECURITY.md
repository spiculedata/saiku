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

- **Dependency scanning:** Snyk on every push to `development`;
  OWASP Dependency-Check via `mvn -P security verify` on release
  builds. Findings above CVSS 7.0 block release.
- **Secrets scanning:** GitHub secret scanning enabled repo-wide;
  commit hooks reject common credential patterns.
- **Fail-closed defaults.** Non-obvious ones: the AI policy guard
  defaults to `SCHEMA_ONLY` in production so AI endpoints don't
  return aggregated data or raw rows without explicit operator
  opt-in; the launcher refuses to serve in production while the
  default admin password (`admin/admin`) is unchanged; observability
  is opt-in so no data leaves the box unless configured.
- **Vulnerability triage.** Findings are logged internally with a
  planned fix window; the fix + disclosure ship together per the
  agreed timeline.

Full production security posture is at
[docs.saiku.bi/security/posture](https://docs.saiku.bi/security/posture/).

## Contact

- **Security disclosures:** [security@saiku.bi](mailto:security@saiku.bi)
- **General inquiries:** [hello@saiku.bi](mailto:hello@saiku.bi)
- **Governance questions:** [tom@spicule.co.uk](mailto:tom@spicule.co.uk)

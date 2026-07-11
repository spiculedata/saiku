# Saiku project governance

_Last reviewed: 2026-07-10_

This document explains how Saiku is governed: who decides what,
how decisions get made, who has commit rights, and how those
rights are gained and lost. Enterprise buyers evaluating Saiku for
production regularly ask for this document. Contributors reading
it will find the process transparent and light-touch.

## Structure

Saiku is stewarded by **Spicule Ltd**, the maintaining company. Day-
to-day maintenance, release management, and roadmap curation happen
inside Spicule. External contributors are welcome and their work
lands in mainline releases.

- **Maintaining organization:** [Spicule Ltd](https://spicule.co.uk),
  incorporated in England & Wales.
- **Primary maintainer:** Tom Barber ([tom@spicule.co.uk](mailto:tom@spicule.co.uk)).
- **Commercial product:** [Saiku Cloud](https://cloud.saiku.bi) — the
  same binary self-hosted operators run, plus operational plumbing.
  Cloud revenue funds the open-source work.

## Decision-making

Saiku uses a **lazy consensus** model for day-to-day changes.

- A pull request is deemed accepted when a maintainer approves it
  and CI is green. No formal quorum required for routine work.
- **Substantive changes** (breaking API changes, licensing changes,
  removing a shipped feature, cutting a major release) require
  explicit discussion on a GitHub issue with at least 72 hours for
  community response before merge.
- **Roadmap changes** (adding or removing items from
  [`ROADMAP.md`](./ROADMAP.md)) require the same 72-hour window.
- **Security decisions** — see [`SECURITY.md`](./SECURITY.md) — go
  through a private disclosure process and aren't debated in the
  open until a fix has shipped.

Where consensus can't be reached, the primary maintainer has the
final call. That happens rarely; in practice most disagreements
resolve in the PR conversation.

## Commit rights

Three tiers of GitHub access:

- **Contributor** — anyone who's opened a PR. No repo permissions;
  work lands via PR review.
- **Committer** — write access to the repo. Granted by consensus of
  existing committers after sustained quality contribution
  (typically 5+ merged PRs across the codebase). Committers can
  merge PRs (their own excluded), triage issues, and approve
  routine changes.
- **Maintainer** — admin access. Currently: Tom Barber. Additional
  maintainers are added by unanimous agreement of existing
  maintainers when the load or the bus factor demands it.

Rights are removed for demonstrated bad-faith behavior (violating
the [Code of Conduct](./CODE_OF_CONDUCT.md), knowingly landing
malicious code) or on request. Inactive committers keep their
rights unless they ask to be removed.

Current committers are listed at [MAINTAINERS.md](./MAINTAINERS.md)
when we have more than one; today the maintainer list is `Tom
Barber` alone.

## The bus factor honesty

Saiku's daily contribution graph today is dominated by one person
(`Tom Barber`). We're aware; it's not a state we want to stay in.
Steps we're actively taking:

- **Library extraction.** The load-bearing Ossie / OSI parser and
  Calcite adapter were split into
  [`bi.saiku.ossie:ossie-core`](https://github.com/spiculedata/ossie)
  and `bi.saiku.ossie:ossie-sql` — Apache 2.0, in their own
  repository, independently maintainable by any JVM contributor
  without touching the main Saiku codebase.
- **Public roadmap.** [`ROADMAP.md`](./ROADMAP.md) makes the
  direction of travel visible so external contributors can plan
  around it.
- **Onboarding.** [`CONTRIBUTING.md`](./CONTRIBUTING.md) is
  refreshed so a new contributor can go from clone to merged PR
  without asking for hand-holding.
- **Pentaho community.** The natural pool of external maintainers
  is the Pentaho / Mondrian community that Saiku already serves.
  We reach out actively when someone contributes and shows
  interest.

Enterprise buyers who need a stronger continuity guarantee can
purchase [Saiku Cloud](https://cloud.saiku.bi) or an
[Enterprise support contract](https://saiku.bi/#pricing), both of
which are backed by Spicule Ltd under commercial terms.

## Licensing

Saiku ships under **Apache License 2.0 with EPL 1.0 exemptions**
(the same dual license Mondrian has used since Pentaho). Any
change to the licence would go through the 72-hour substantive-
change process AND would require explicit sign-off from every
copyright holder whose contributions materially remain in the
codebase.

The published pledge — the version of Saiku you self-host is the
version Spicule runs in Cloud — is a governance commitment, not
just marketing copy. Relicensing to a source-available or
commercial-only licence in the future would violate that pledge and
isn't on any roadmap.

## Relationship with the Ossie project

Saiku is a reference consumer of the Apache Ossie / Open Semantic
Interchange specification. Saiku engineers who submit changes to
the Apache Ossie project do so as individual contributors under
the Apache CLA — they don't act as agents of Spicule. This
separation lets Ossie remain a genuinely vendor-neutral standard
even while Saiku is a large consumer.

## Contact

- **Governance questions:** [tom@spicule.co.uk](mailto:tom@spicule.co.uk)
- **Security disclosures:** see [`SECURITY.md`](./SECURITY.md)
- **Code of conduct violations:** see
  [`CODE_OF_CONDUCT.md`](./CODE_OF_CONDUCT.md)
- **Commercial:** [hello@spicule.co.uk](mailto:hello@spicule.co.uk)

# Chainguard base image evaluation (saiku#1991)

**Snapshot:** 2026-09-28. Evaluated against the current `Dockerfile` base,
`eclipse-temurin:21-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f`.

**Methodology:** no Docker daemon was available in the evaluation
environment, so findings below come from talking to the registries
directly over HTTPS (anonymous token exchange, manifest/config/layer
fetches against `cgr.dev` and `registry-1.docker.io`) rather than a local
`docker build`/`docker run`. Every claim marked *(verified)* was checked
this way today; everything else is sourced from Chainguard's own docs and
cited inline. Commands are reproducible with `curl` alone (see footnotes).

## Recommendation: adopt-if-X — not viable today

Keep `eclipse-temurin:21-jre-noble` (already digest-pinned per #1919, already
non-root per #1989) for now. Chainguard becomes worth a real migration once
**both** of these are true:

1. Someone with authority to spend money/create accounts decides to pay for
   (or enroll and get entitled to) a Chainguard tier that includes an
   LTS-pinned, digest-stable JRE 21 image — the free/anonymous tier does not
   offer one today (see *Cost/tier* below). This is a maintainer decision,
   not an engineering one.
2. `docker/saiku-entrypoint` is rewritten so it doesn't need a shell — the
   runtime image we'd actually want has none (see *Fit* below). This is real
   engineering work, not a Dockerfile tweak.

Until both land, the issue's suggested fallback — a digest-pinned
`eclipse-temurin:21-jre-alpine` — is a smaller, lower-risk change that gets
part of the size/CVE-surface win without a new account dependency or an
entrypoint rewrite. It's not evaluated in depth here (out of scope for this
ticket) but is worth a follow-up spike if the base-image CVE count is the
main pain point driving this.

## 1. Cost / tier — confirms the issue's concern, with a twist

The old fully-anonymous "public tier" still exists for a curated image list,
but it does **not** include what we need:

- `cgr.dev/chainguard/jre` and `cgr.dev/chainguard/jdk` — anonymous token
  exchange succeeds with **zero login, zero account, zero corporate email**
  *(verified: `curl https://cgr.dev/token?scope=repository:chainguard/jre:pull`
  → `200`, no auth header sent)*. These track upstream's newest OpenJDK
  release, not an LTS.
- `cgr.dev/chainguard/jre-lts` — the image that would actually track the
  current LTS (JRE 21 today) — returns **`403 Forbidden`** on the same
  anonymous token exchange *(verified)*. It sits behind either Chainguard's
  "Catalog Starter" free-with-signup tier (requires a corporate email,
  choosing 5 images out of the catalog, and a manual review/approval step —
  [chainguard.dev/unchained/introducing-chainguard-catalog-starter](https://www.chainguard.dev/unchained/introducing-chainguard-catalog-starter))
  or a paid Per-Image/Catalog tier with a contractual CVE SLA
  ([chainguard.dev/pricing](https://www.chainguard.dev/pricing)).
- Only `:latest` / `:latest-dev` tags exist on any free path — no
  `openjdk-21`-style version tag *(verified: `chainguard/jre:openjdk-21` and
  `:openjdk-21-dev` both `404`)*. Digest-pinning `:latest` today is possible
  and is Chainguard's own recommended pattern for free-tier reproducibility,
  but it pins *whatever OpenJDK major happens to be "latest" on the day you
  resolve it* — see the version-drift problem below.

**The version-drift problem.** `chainguard/jre:latest` is not JRE 21 — it's
tracking a newer OpenJDK major (Chainguard's own image notes mention
"Java 25+" behavior changes). Because the free tier gives us `jre`
(latest-tracking) but not `jre-lts` (LTS-tracking), the only free path pins
us to a moving major-version target: every time we (or an automated digest
bump) re-resolve `:latest`, we could silently jump JRE majors with no
review. This is exactly the class of change the team already pushed back on
in PR#2004 (`eclipse-temurin:21-jre-noble` → `24-jre-noble`, closed without
merging — Java 24 removes APIs deprecated since Java 9–17). Adopting the
free Chainguard `jre` image trades one controlled, deliberate base-image
bump per year for an uncontrolled one every time upstream OpenJDK ships.

**Bottom line:** getting a stable, reproducible, LTS-tracked Chainguard JRE
requires enrolling in a paid or gated tier. That's a legitimate path — it's
what Chainguard is built to sell — but it's a decision for whoever owns the
budget/org account, not something this evaluation can execute.

## 2. Licensing — not a blocker

Chainguard's Master Software License Agreement governs use of their
*registry service*, not the packages inside the image. §1.2 states the
agreement doesn't limit or supersede "the terms of a free and open source
software license of any particular component," and that each component
"is governed by a license that permits you to run, copy, modify, and
redistribute" it
([chainguard.dev/legal/software-license-agreement-250522](https://www.chainguard.dev/legal/software-license-agreement-250522)).
Wolfi packages (including the OpenJDK build) carry their original upstream
licenses — the same GPLv2+Classpath-Exception OpenJDK license Temurin ships
today. There's nothing here that conflicts with distributing Saiku under
Apache-2.0/EPL-1.0; this question is a non-issue either way.

## 3. Fit — the real blocker

`docker/saiku-entrypoint` is a Bash script: it checks `saiku-home`
writability and `secret.key` readability before boot, and assembles
`JAVA_OPTS` from several env vars. The runtime image we'd want
(`chainguard/jre:latest`) has **no shell anywhere in the image**
*(verified: downloaded and `tar t`'d all 11 layers of the amd64
`jre:latest` image — no `bin/sh`, `bin/bash`, or `bin/busybox` in any of
them)*, and its OCI config sets `Entrypoint: ["/usr/bin/java"]` directly —
there's no shell to exec into even implicitly. `docker/saiku-entrypoint`
cannot run on this image as-is.

Two ways forward, neither of which is a config change:

- **Port the entrypoint logic into Java.** The launcher already has a
  Picocli `serve` command (`SaikuLauncher.ServeCommand`); the pre-flight
  checks and `JAVA_TOOL_OPTIONS`/`OTEL_*` wiring could move there, and the
  container `ENTRYPOINT` becomes `["/usr/bin/java", "-jar",
  "/app/saiku.jar", "serve", ...]` directly. This is the "do it properly"
  option but touches Java code, not just the Dockerfile.
- **Ship `jre:latest-dev` at runtime instead of `jre:latest`.** The `-dev`
  variant does carry a shell (not independently re-verified byte-for-byte,
  but it's Chainguard's documented purpose for `-dev` variants — "include
  additional software... typically not necessary in production
  environments"). This needs no entrypoint rewrite, but it's ~26MB larger
  compressed than the minimal variant *(verified: `latest-dev` amd64 layers
  total 132.9MB vs `latest`'s 107.3MB)* and reintroduces exactly the
  general-purpose userland (package manager, busybox utilities) this
  evaluation is trying to get away from. It undercuts the pitch.

## 4. Practicalities — same shell problem, smaller stakes

`curl`/`sha256sum` are used only at **build time** to fetch and verify the
OTel agent jar — that step can run in an earlier build stage on
`chainguard/wolfi-base` (which does have a shell, apk, curl — anonymous pull
also works *(verified: token exchange `200`)*) or on `jre:latest-dev`, then
`COPY --from=` the verified jar into the minimal final stage. This part is
straightforward regardless of which way the entrypoint question is resolved
— multi-stage builds already separate "things that run at build time" from
"things that run at runtime" and Docker doesn't require them to be the same
base image.

The `HEALTHCHECK` also shells out to `curl` today; same fix (multi-stage
`COPY` of a static curl, or drop the shell-form `HEALTHCHECK` and re-do the
liveness check some other way) would be needed for a shell-less final
image.

## 5. Multi-arch — parity confirmed

`chainguard/jre:latest`'s manifest index carries both `linux/amd64` and
`linux/arm64` entries *(verified)*, matching what Saiku already publishes.
No gap here.

## 6. Size, for what it's worth

*(verified via manifest inspection, not a local build — compressed layer
sizes only, not decompressed/runtime size)*

| Image | Compressed size | Java version |
|---|---|---|
| `eclipse-temurin:21-jre-noble` (current) | ~95.2 MB | 21 (LTS, pinned) |
| `chainguard/jre:latest` (free tier) | ~107.3 MB | latest upstream (not 21; drifts) |
| `chainguard/jre:latest-dev` (free tier) | ~132.9 MB | same, plus shell/tools |

Contrary to the usual "distroless is smaller" expectation, the free
Chainguard `jre` image is **larger** than what we ship today, almost
certainly because it's tracking a newer (larger) OpenJDK release rather
than JRE 21. This isn't a apples-to-apples comparison — a hypothetical
`jre-lts` pinned to 21 might come in smaller — but it means the "cut the
noise" motivation from the issue can't be assumed to come with a size win
on the path that's actually free today.

CVE-count delta was not measured — no scanner (trivy/grype) was available
in this evaluation environment and standing one up was out of scope for an
evaluation ticket. Whoever picks up the "adopt-if-X" path should run
`trivy image` (or equivalent) against both bases as part of that work.

## Acceptance-criteria checklist

- [x] Written recommendation: **adopt-if-X** (paid/enrolled LTS-pinned
      image + entrypoint rewritten off Bash), not viable as a drop-in today.
- [x] Explicit answer on cost tier and licence: cost tier is the actual
      blocker (LTS image is gated); licence is not a concern.
- [ ] Spike branch with a working build — skipped; not viable on the free
      tier without either paying/enrolling or accepting JRE version drift,
      and no Docker daemon was available to build/measure one locally.
- [x] What would have to change to become viable — §6 above / the two
      bullets under *Recommendation*.

## Related

- saiku#1989 (non-root `USER`) — already done; Chainguard images already
  default to a non-root uid (`65532`) so this would converge, not regress.
- saiku#1990 / PR #2001 (SBOM + provenance) — independent of this decision;
  already gets Saiku SBOMs without Chainguard.
- saiku#1919 (Docker/CI hardening bundle, incl. digest pinning) — the
  current base is already digest-pinned; that item doesn't change either
  way here.

— hive: backend=claude model=claude-sonnet-5

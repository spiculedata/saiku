# Chainguard base image (saiku#1991 evaluation, adopted in saiku#2462)

## Decision: adopted (2026-11)

The Docker image now runs on `cgr.dev/concepttocloud.com/jre:openjdk-21`,
digest-pinned, with build stages on the free public
`cgr.dev/chainguard/wolfi-base`. The two conditions the September evaluation
set are both met:

1. **Cost/tier.** The Chainguard org `concepttocloud.com` is provisioned with
   the `jre` image: LTS-pinned `openjdk-21` and `openjdk-21-dev` tags,
   multi-arch (amd64 + arm64). No version drift, no free-tier compromise.
2. **No shell needed.** `docker/saiku-entrypoint` is gone. Its pre-boot checks
   and env bridging moved into the launcher (`ContainerPreflight`, called from
   `serve`), and the `HEALTHCHECK` is a launcher subcommand
   (`healthcheck`), so the final image has no shell, package manager or curl.

Why: the base-image attack surface drops sharply. Scanning the same launcher
jar on the old and new base with grype 0.118 (DB built 2026-11-21): **311
findings before (Critical 8, High 19, Medium 247, Low 34, Negligible 3), 30
after (Critical 8, High 11, Medium 10, Low 1)**. The OS/base-package findings
went from 287 to 5; the remainder are Java dependencies inside the Saiku jar
itself, which the base does not affect. The base JRE is OpenJDK 21.0.13.1, which
also carries the fix for CVE-2026-114938 (HotSpot x86-64 Base64 decode intrinsic),
so the `-XX:-UseBASE64Intrinsics` interim flag was dropped from the image.

### What the image looks like now

| Concern | How it works |
|---|---|
| Runtime base | `cgr.dev/concepttocloud.com/jre:openjdk-21@sha256:...` (index digest, both arches) |
| Build stage | `cgr.dev/chainguard/wolfi-base@sha256:...`: downloads and sha256-verifies the OTel agent, writes the passwd/group entries for uid/gid 10001, creates the writable dirs |
| User | uid/gid `10001:10001` (unchanged), `HOME=/home/saiku`, `SAIKU_HOME=/app/saiku-home` |
| Entrypoint | exec-form `java -jar /app/saiku.jar`, `CMD ["serve"]` |
| Pre-boot checks | `ContainerPreflight` in the launcher: saiku-home writable (real write probe), `conf/secret.key` readable. Same fail-closed `FATAL:` refusal as before |
| Health | `HEALTHCHECK CMD ["java", ..., "healthcheck"]` (HEAD `/rest/saiku/info`, exit 0/1) |
| Fonts for PNG (#1810) | Already in the Chainguard JRE (fontconfig, freetype, DejaVu). Verified in the built image: AWT `SansSerif` text renders to PNG. Nothing is copied from the build stage and the `-dev` variant is not needed |
| OTel agent | Present at `/opt/saiku/otel/opentelemetry-javaagent.jar`; attach with `JDK_JAVA_OPTIONS=-javaagent:...` (see `docs/observability.md`) |

### Behaviour changes for operators

- **`JAVA_OPTS`.** Without a shell nothing can expand it into the `java`
  command line. The launcher still honours the `-Dkey=value` entries in
  `JAVA_OPTS` (the documented use: Saiku settings), but never overrides a
  property set another way. JVM flags in `JAVA_OPTS` (`-Xmx`, `-XX:`,
  `-javaagent:`) are **not applied** and are reported on stderr. Pass JVM flags
  via `JDK_JAVA_OPTIONS` (added to the image's defaults) or
  `JAVA_TOOL_OPTIONS` (replaces them).
- **OTel agent** is no longer attached automatically when
  `OTEL_EXPORTER_OTLP_ENDPOINT` is set; add the `-javaagent` flag explicitly.
  `OTEL_SERVICE_NAME` still defaults to `saiku`.
- **`docker run <image> <command>`** no longer execs an arbitrary command: the
  arguments go to the launcher (`serve`, `healthcheck`, `eval`, ...). To run
  something else use `--entrypoint`. The image has no shell, so `docker exec
  ... sh` does not work; use the `-dev` variant of the base for a debugging
  image if you need one.
- **Fixing volume ownership** can no longer use the image's own `chown`. Use a
  throwaway image, e.g. `docker run --rm -v <vol>:/h --user 0
  cgr.dev/chainguard/wolfi-base chown -R 10001:10001 /h`.
- **Building the image needs a Chainguard pull token** (below).

### Pull token for maintainers

CI logs in to `cgr.dev` with the repo secrets `CHAINGUARD_IDENTITY_ID`
(username) and `CHAINGUARD_TOKEN` (password, passed on stdin by
`docker/login-action`) in `docker.yml` and `release.yml`. The token is a
Chainguard **pull token that expires after 30 days**: rotate it at least every
30 days (create a new pull token for the `concepttocloud.com` org in the
Chainguard console or with `chainctl auth pull-token`, then update both
secrets). An expired token fails the image build with an authentication error
on the base-image pull, and `docker.yml` fails loudly rather than skipping when
the secrets are missing on a run that would publish. Fork and Dependabot PRs
do not receive the secrets: the image build is skipped for them (the job ends
neutral) and a maintainer re-runs it from a same-repo branch. Local builds:
`chainctl auth configure-docker` (use a throwaway `DOCKER_CONFIG` if you do not
want it in your main one).

To roll the base forward, resolve the new index digest with
`docker buildx imagetools inspect cgr.dev/concepttocloud.com/jre:openjdk-21`
and update the `FROM` line (same for wolfi-base).

---

# Original evaluation (2026-09-28, historical)

Everything below is the September evaluation that preceded the decision above;
it is kept for the reasoning. Its "not viable today" recommendation was
superseded by the adoption.

**Snapshot:** 2026-09-28. Evaluated against the then-current `Dockerfile` base,
`eclipse-temurin:21-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f`.

**Methodology:** no Docker daemon was available in the evaluation
environment, so findings below come from talking to the registries
directly over HTTPS (anonymous token exchange, manifest/config/layer
fetches against `cgr.dev` and `registry-1.docker.io`) rather than a local
`docker build`/`docker run`. Every claim marked *(verified)* was checked
this way that day; everything else is sourced from Chainguard's own docs and
cited inline. Commands are reproducible with `curl` alone (see footnotes).

## Recommendation at the time: adopt-if-X

Keep `eclipse-temurin:21-jre-noble` until both of these were true: a paid or
enrolled Chainguard tier with an LTS-pinned JRE 21, and an entrypoint that
does not need a shell. Both are now done (see the decision above).

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

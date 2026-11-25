# Saiku demo image — bundles the launcher (REST + UI + native MCP on :8080)
# in a single container. Since #878 the MCP endpoint lives inside saiku-webapp
# at /rest/saiku/api/mcp; MCP hosts (Claude Desktop, Cursor, …) authenticate
# with per-user Basic credentials over the same chain as the AI REST API.
# No separate stdio binary required.
#
# Build flow (release.yml / docker.yml):
#   1. The jar job builds the launcher fat JAR under saiku-launcher/target/.
#   2. The docker job stages it into ./build-context/saiku.jar.
#   3. This Dockerfile COPYs it in. No Maven runs at image build —
#      avoids needing GH Packages auth inside the container.
#
# Base images are DIGEST-PINNED (#1989 / #1919 item 18a). The tag is kept
# alongside the digest for humans; the digest is what actually resolves, so a
# re-tagged or tampered upstream can't change what we build on.
#
# Runtime base: Chainguard's `jre` (saiku#2462, evaluation in
# docs/docker-chainguard-evaluation.md). It is a distroless-style image: no shell,
# no package manager, no curl. That is why this file is multi-stage and why the old
# bash entrypoint is gone (its checks live in the launcher's `serve` command, the
# HEALTHCHECK is the launcher's `healthcheck` command). The image lives in the
# `concepttocloud.com` Chainguard org, so building needs a pull token
# (`docker login cgr.dev`; CI does it from CHAINGUARD_IDENTITY_ID / CHAINGUARD_TOKEN,
# see docs/docker-chainguard-evaluation.md for rotation). To roll the base forward:
#   docker buildx imagetools inspect cgr.dev/concepttocloud.com/jre:openjdk-21
# and update the digest below (and the wolfi-base digest the same way:
#   docker buildx imagetools inspect cgr.dev/chainguard/wolfi-base:latest).
#
# Fonts for headless PNG rendering (saiku#1810): AWT resolves logical fonts through
# fontconfig and dies with "Fontconfig head is null" without it. The Chainguard jre
# image already ships fontconfig, freetype and DejaVu (checked from its apk db and
# SBOM), so nothing is installed here; the PNG path is exercised in the image test.
FROM cgr.dev/concepttocloud.com/jre:openjdk-21@sha256:4dbbdd8c594eca71b6d9f786868dc1978f498d6449823c1f058b1beda9189023 AS jre

# ---------------------------------------------------------------------------
# Build stage: everything that needs a shell happens here, on the free public
# wolfi-base, and is staged into /rootfs for a plain COPY into the final image.
# ---------------------------------------------------------------------------
FROM cgr.dev/chainguard/wolfi-base:latest@sha256:28c39be5a5e1e9b85081c10740e8495f6518a865b23bc6a486637c4b21e74b43 AS rootfs
ARG OTEL_AGENT_VERSION=2.28.1
ARG OTEL_AGENT_SHA256=faa89bdeebf9b1f52be4a4374689176717b02a59df2d8f8b6eb9aa39f9292589

# OpenTelemetry Java agent: side-loaded, attached at run time only when the
# operator asks (JDK_JAVA_OPTIONS=-javaagent:..., see docs/observability.md).
# Pinned by checksum so a tampered Maven Central response can't slip in a
# different binary.
RUN set -eux; \
    apk add --no-cache curl; \
    mkdir -p /rootfs/opt/saiku/otel; \
    curl -fsSL -o /rootfs/opt/saiku/otel/opentelemetry-javaagent.jar \
      "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar"; \
    echo "${OTEL_AGENT_SHA256}  /rootfs/opt/saiku/otel/opentelemetry-javaagent.jar" | sha256sum -c -

# Non-root runtime user (#1989, CWE-250). uid/gid are FIXED and DOCUMENTED at
# 10001:10001 so operators who bind-mount a host directory for saiku-home can
# `chown 10001:10001` it deterministically across upgrades. Do NOT change these
# numbers without a migration note. The image has no useradd, so the passwd/group
# entries are appended to the base image's own files here.
#
# HOME is /home/saiku (owned by the user), NOT /app: /app and the jar stay
# root-owned, so a HOME there would be read-only. The JVM writes per-user scratch
# under HOME (e.g. ~/.java/fonts for AWT font caching used by chart/PDF/XLS
# export rendering).
COPY --from=jre /etc/passwd /etc/group /rootfs/etc/
RUN set -eux; \
    echo 'saiku:x:10001:10001:saiku:/home/saiku:/sbin/nologin' >> /rootfs/etc/passwd; \
    echo 'saiku:x:10001:' >> /rootfs/etc/group; \
    mkdir -p /rootfs/home/saiku /rootfs/app/saiku-home

# ---------------------------------------------------------------------------
# Final stage: the Chainguard JRE. No shell, no package manager.
# ---------------------------------------------------------------------------
FROM jre
ARG JAR_PATH=build-context/saiku.jar

WORKDIR /app

# Ownership, scoped tightly (#1989 S1 review). ONLY /app/saiku-home (the write
# path: data, repository, sessions, logs, plugins, conf/secret.key) and the
# user's HOME are handed to the runtime uid. Everything the JVM merely EXECUTES
# stays root-owned and world-readable: /app/saiku.jar and /opt/saiku/otel/*.jar.
# A code-exec-as-saiku compromise (the 4.8.0 RCE class this hardening defends
# against) cannot rewrite or unlink the very jar/agent it runs from.
COPY --from=rootfs /rootfs/etc/passwd /rootfs/etc/group /etc/
COPY --from=rootfs /rootfs/opt/saiku /opt/saiku
COPY --from=rootfs --chown=10001:10001 /rootfs/home/saiku /home/saiku
# The chown must precede the VOLUME line: Docker seeds a fresh (empty) named or
# anonymous volume from the image mount-point, carrying ownership across. Bind
# mounts and PRE-EXISTING volumes are NOT chowned by Docker (see the upgrade note
# in CHANGELOG); the launcher's serve pre-flight fails closed if the home is unusable.
COPY --from=rootfs --chown=10001:10001 /rootfs/app/saiku-home /app/saiku-home
COPY ${JAR_PATH} /app/saiku.jar

ENV SAIKU_HOME=/app/saiku-home \
    HOME=/home/saiku \
    OTEL_SERVICE_NAME=saiku

# JVM safety defaults (#1989 / #1919 item 18a). Set via JAVA_TOOL_OPTIONS so the
# JVM applies them regardless of how it is launched. With no shell there is no
# JAVA_OPTS merging any more: `-e JAVA_TOOL_OPTIONS=...` REPLACES this value, so
# add flags through JDK_JAVA_OPTIONS instead, which the java launcher reads in
# addition (see docs/operator-hardening.md). The JVM prints "Picked up
# JAVA_TOOL_OPTIONS: ..." to stderr on every launch; that line is benign.
#   -XX:+ExitOnOutOfMemoryError  fail fast on OOM so the orchestrator restarts
#                                a clean JVM instead of limping on a corrupt heap
#   -XX:MaxRAMPercentage=75      size the heap from the container memory limit
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75"

VOLUME ["/app/saiku-home"]
EXPOSE 8080

# Liveness probe: the launcher's own `healthcheck` subcommand (HEAD
# /rest/saiku/info, anonymous; saiku#866 added the HEAD handler for monitors).
# No curl in this image. -Xmx64m beats JAVA_TOOL_OPTIONS' percentage so the probe
# JVM stays small. start-period is generous: first boot seeds saiku-home and runs
# the H2 FoodMart bootstrap (~30s, ~200MB) before the port answers.
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
  CMD ["/usr/bin/java", "-Xmx64m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-jar", "/app/saiku.jar", "healthcheck"]

# Runtime user, numeric because the image has no name lookup tooling to rely on.
USER 10001:10001

ENTRYPOINT ["/usr/bin/java", "-jar", "/app/saiku.jar"]
CMD ["serve"]

# Phase 1 Group E — Distribution (implementation plan)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute task-by-task.

**Goal:** A single `docker run saiku/saiku` works. GitHub tag → automated JAR + Docker image release. Optional Homebrew tap.

**Base branch:** `development` (post Phase 1 Group D merge).

**Tech stack:** Docker (distroless), GitHub Actions, Homebrew tap.

**Effort:** ~1 session.

---

## Tasks

### Task 1E.1 — Multi-stage Dockerfile

Create `Dockerfile` at repo root:
- Stage 1: `maven:3.9-eclipse-temurin-21` → `mvn -pl saiku-server -am -DskipTests package`
- Stage 2: `gcr.io/distroless/java21-debian12:nonroot` → copy the fat JAR, `ENTRYPOINT ["java","-jar","/app/saiku.jar"]`, default `CMD ["serve"]`
- `HEALTHCHECK` hits `/actuator/health` (Spring Boot actuator)
- `EXPOSE 8080`
- Image size target: < 250MB

### Task 1E.2 — `.dockerignore`

Exclude `target/`, `.git/`, `node_modules/`, `data/`, `saiku-data/`, IDE files. Keeps the build context small and deterministic.

### Task 1E.3 — GitHub Actions release workflow

New `.github/workflows/release.yml`:
- Trigger: `push` on `v*` tags
- Build the fat JAR with `mvn -B -ntp -DskipTests package`
- Build the Docker image (docker/build-push-action@v5) with multi-arch amd64/arm64
- Push to GHCR (`ghcr.io/osbi/saiku:$TAG` + `:latest`)
- Attach JAR to a GitHub Release via `softprops/action-gh-release`
- Sign the Docker image + JAR with Sigstore cosign (keyless, OIDC)

### Task 1E.4 — Version-stamp the JAR

Ensure the built JAR's MANIFEST.MF carries `Implementation-Version` and Git SHA. `saiku version` reads this.

### Task 1E.5 — (Optional) Homebrew tap

Separate repo `osbi/homebrew-saiku` with a Ruby formula that downloads the released JAR and installs a `saiku` shell shim. Published tap: `brew tap osbi/saiku && brew install saiku`.

### Task 1E.6 — Quickstart section in README

Three-line install doc:
```bash
docker run -p 8080:8080 ghcr.io/osbi/saiku:latest
# or
curl -LO https://github.com/OSBI/saiku/releases/latest/download/saiku.jar && java -jar saiku.jar serve
# or
brew install osbi/saiku/saiku && saiku serve
```

---

## Exit criteria

- `docker run -p 8080:8080 ghcr.io/osbi/saiku:latest` works end-to-end; `curl localhost:8080/saiku/rest/saiku/api/info/release` returns 200
- GitHub Actions release workflow green on a test tag (e.g. `v3.18-rc1`)
- Docker image signed; `cosign verify ghcr.io/osbi/saiku:<tag>` passes
- (If Homebrew in scope) `brew install osbi/saiku/saiku` works on a clean Mac
- Image size < 250MB
- README quickstart works on a fresh machine

## Risk-ordered task list

| # | Risk | Reversible? | Ship independently? |
|---|------|-------------|---------------------|
| 1E.1 Dockerfile | Low | Yes | Yes |
| 1E.2 .dockerignore | Trivial | Yes | Yes |
| 1E.3 Release workflow | Low | Yes | Yes |
| 1E.4 Version stamp | Low | Yes | Yes |
| 1E.5 Homebrew tap | Low | Yes | Can defer indefinitely |
| 1E.6 README quickstart | Trivial | Yes | Last |

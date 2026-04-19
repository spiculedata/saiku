# Phase 2 — Kill Jackrabbit, move state to the filesystem (implementation plan)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute task-by-task.

**Goal:** Saiku's state (datasources, Mondrian schemas, saved queries, dashboards) lives in a plain folder you can commit to git. No JCR, no Jackrabbit, no embedded content repo.

**Base branch:** `development` (post-#751).

**Tech stack:** Jackson YAML, Jakarta validation (once Spring Boot 4 lands — until then, manual validation), JUnit 4, Testcontainers only where a real JCR comparison matters for the migration tool.

**Effort estimate:** 2–3 focused sessions. Tonight's session (Phase 1D wrap) established that the surface is 47 `IDatasourceManager` methods backed by a 968-line `JackRabbitRepositoryManager` and a 590-line read-only `ClassPathRepositoryManager` that already uses `javax.jcr.*` types as its internal model. That coupling is why Phase 2 is its own plan, not a one-commit swap.

---

## Filesystem layout (first-class public spec)

```
saiku-data/
  datasources/          # one *.yml per connection
    sales.yml
  schemas/              # Mondrian XMLs; will be augmented by Phase 3 YAML
    sales.mondrian.xml
  queries/              # *.saiku.json saved queries
    my-query.saiku.json
  dashboards/           # *.yml dashboard configs
  users/
    users.yml           # dev-mode auth; OIDC replaces this in Phase 7
    roles.yml           # role → path-prefix ACL
  .meta/
    audit.log           # append-only action log
    schema.json         # folder version stamp for migrations
```

Example `datasources/sales.yml`:
```yaml
name: sales
type: OLAP
connection:
  driver: mondrian.olap4j.MondrianOlap4jDriver
  url: jdbc:mondrian:Jdbc=jdbc:postgresql://...;JdbcUser=saiku;Catalog=schemas/sales.mondrian.xml
  username: saiku
  # password from env: SAIKU_DS_SALES_PASSWORD
security:
  role_read: [viewer, analyst]
  role_write: [admin]
```

Principles:
- **One file per logical artefact.** No JCR nodes, no binary blobs. Git-diffable.
- **Secrets never on disk.** `password` pulled from env var or Vault; filesystem file names the secret key, not the value.
- **Schema version baked in.** `.meta/schema.json` says `{ "version": 1 }` so migrations are deterministic.
- **Atomic writes.** Every update = write to `.tmp`, fsync, rename. No corrupted half-writes.

---

## Internals & ordering

### Task 2.1 — Expand `IRepositoryManager` into a proper 2026 interface

`saiku-core/saiku-service/src/main/java/org/saiku/repository/IRepositoryManager.java` currently exposes only 7 methods (init/shutdown, file CRUD, ACL). The 45-method surface lives implicitly on `JackRabbitRepositoryManager`. Promote the missing methods onto the interface so a new impl doesn't inherit the Jackrabbit-specific extras.

Sub-steps:
- Read `JackRabbitRepositoryManager.java` top-to-bottom, list every public method.
- Categorise: core (belongs on interface) vs. Jackrabbit-only (stays private or gets deleted when JCR leaves).
- Add missing core methods to `IRepositoryManager`. Keep signatures simple — refactor parameter shapes only where Jackrabbit leaked `javax.jcr.*` types into them (replace `Node` with `RepositoryFile`, `Session` with an opaque context token, etc.).
- `ClassPathRepositoryManager` + `JackRabbitRepositoryManager` both compile against the new interface. Some `ClassPathRepositoryManager` methods will throw `UnsupportedOperationException` for the new additions — acceptable, fix in 2.2.

Exit: green `mvn verify` on the interface expansion alone.

### Task 2.2 — Build `FilesystemRepositoryManager`

New class in the same package. Copy `ClassPathRepositoryManager` as starting point and evolve:
- Replace classpath reads with `java.nio.file.Files` reads/writes.
- Drop `javax.jcr.*` internal types. Use `RepositoryFile` (existing DTO) for all file metadata.
- Implement every method on the expanded interface. No `UnsupportedOperationException`s.
- Write-path semantics: temp-file + atomic rename.
- ACL: walk `users/roles.yml` and match path prefixes.

Unit tests per method group (CRUD, ACL, file metadata). Use `@TempDir` or similar for isolated filesystem fixtures.

Exit: `FilesystemRepositoryManager` passes a shared test suite that both it and `JackRabbitRepositoryManager` can run against (behavioural equivalence gate).

### Task 2.3 — Config flag + Spring wiring

`application.yml` (or `saiku.properties` for now):
```yaml
saiku:
  repository:
    backend: filesystem   # or: jcr
    data-dir: ./saiku-data
```

Spring `@Configuration` picks the bean to instantiate based on the property. Default to `filesystem` for new installs; existing installs on `jcr` keep working until they run the migration tool.

### Task 2.4 — `saiku migrate jcr-to-fs` CLI command

Picocli subcommand under the CLI scaffolding that lands in Phase 1 Group D (or bring forward if Group D hasn't shipped by now). Behaviour:
- `saiku migrate jcr-to-fs --source /path/to/jcr-repo --target /path/to/saiku-data`
- Spins up Jackrabbit against `--source` read-only, iterates every node, writes equivalent files to `--target`.
- Writes `.meta/schema.json` stamp.
- Refuses to run if `--target` is non-empty and `--force` isn't set.
- Dry-run mode prints the file list it *would* write.

Integration test: round-trip a sample JCR repo snapshot (committed under `saiku-core/saiku-service/src/test/resources/jcr-snapshot/`) through the migration tool and assert the resulting `saiku-data/` matches a golden tree.

### Task 2.5 — ACL implementation

`roles.yml` format:
```yaml
roles:
  admin:
    paths: ["**"]
    actions: [read, write, delete]
  analyst:
    paths: ["datasources/**", "schemas/**", "queries/**"]
    actions: [read]
  viewer:
    paths: ["queries/public/**"]
    actions: [read]
```

Replaces the Spring `SecurityAwareConnectionManager`'s JCR ACL lookups. Simple path-prefix match; no regex until someone needs it.

Unit tests: role-per-path matrix.

### Task 2.6 — Optional git-sync

If `saiku-data/` is a git repo AND `saiku.repository.git.enabled=true`:
- Every write call (`saveFile`, `saveInternalFile`, `setACL`, etc.) after success invokes `git add <path> && git commit -m "<action> <path>" --author="<logged-in-user>"`.
- Use JGit (`org.eclipse.jgit:org.eclipse.jgit`). Add to BOM.
- `.saiku/config.json` tracks repo head so we can detect external writes.

Unit tests: write through the manager, assert a commit landed with the right author/message.

### Task 2.7 — Delete Jackrabbit

Once `FilesystemRepositoryManager` is the default and the migration tool has run end-to-end against a real Jackrabbit repo in CI:
- Delete `JackRabbitRepositoryManager.java`, `MondrianVFS.java`, `RepositoryFileName.java`, `RepositoryVfsFileContent.java`, `RepositoryVfsFileObject.java`, `SaikuWebdavServlet.java`, `SaikuSessionProvider.java`, `ScopedRepo.java`.
- Drop all `org.apache.jackrabbit:*`, `javax.jcr:jcr`, `commons-vfs` dependencies from the BOM and child poms.
- Measure JAR size before/after.
- Unblocks the `javax.servlet` → `jakarta.servlet` sweep deferred in Phase 1C (WebDAV servlet was the blocker).

### Task 2.8 — Behavioural equivalence CI gate

Replay a canonical datasource + query suite through both managers (when both present) and diff. Ensures the migration didn't silently change semantics. Delete after Task 2.7 lands.

---

## Exit criteria

- Fresh install creates `saiku-data/` with sample content; end-to-end works (can load a cube, run a query, save it).
- `saiku migrate jcr-to-fs` converts a real JCR repo with zero data loss (golden-file diff).
- JAR size drops by ~30–40% (measured before/after).
- Jackrabbit absent from `mvn dependency:tree`.
- `jakarta.servlet` sweep proceeds cleanly (verified by re-applying the sed from Phase 1C's attempted sweep).
- Test suite (including new `FilesystemRepositoryManagerTest`) green on JDK 21.

---

## Risk-ordered task list

| # | Risk | Reversible? | Ship independently? |
|---|------|-------------|---------------------|
| 2.1 Expand `IRepositoryManager` | Low | Yes | Yes |
| 2.2 `FilesystemRepositoryManager` | Med | Yes | Yes (behind flag, default off) |
| 2.3 Config flag | Low | Yes | Yes |
| 2.4 Migration CLI | Low | Yes | Yes |
| 2.5 ACL | Med | Yes | Yes |
| 2.6 Git-sync | Low (it's opt-in) | Yes | Yes |
| 2.7 Delete Jackrabbit | **High** — point of no return | No | Last |
| 2.8 Equivalence CI gate | Low | Yes | Yes |

Ship 2.1–2.6 under `--backend=filesystem` as an *additive* flag. 2.7 lands only after external users have had a release cycle to migrate.

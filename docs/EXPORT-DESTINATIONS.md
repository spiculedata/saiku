# Export destinations (saiku#1987)

**Export destinations** are where an export *lands*. The exporters produce bytes; a destination
decides where those bytes go. Saiku ships one first-party destination — **Google Drive** — on top of
a small SPI that lets an installer add S3, OneDrive, Dropbox or a custom HTTP sink **without forking
the export stack**.

This is the file-delivery sibling of `AlertChannel` (email / webhook) and it follows the same
pluggable-bean pattern as `JobHandler`.

## What is *not* this

- **Not the tile-plugin surface** (#1441). Those are sandboxed viz iframes served with
  `connect-src 'none'`; they physically cannot upload a file. Do not overload either API.
- **Not a plugin marketplace.** Signed drop-in JAR discovery is explicitly out of scope; see
  [Not in this issue](#not-in-this-issue).
- **Not a replacement for browser download.** Everyday "download the CSV" still goes through
  `ExporterResource`. This is about delivering to somewhere other than the browser.

## The SPI

Everything lives in `org.saiku.service.export.destination` (module `saiku-core/saiku-service`).

| Type | Role |
|---|---|
| `ExportDestination` | the contract: `id()`, `displayName()`, `configFields()`, `validateConfig()`, `deliver()` |
| `ExportDestinationConfigField` | one admin config field (name, label, required, **secret**, help) — lets Saiku render a form for a destination it knows nothing about |
| `ExportDestinationConfig` | immutable settings + secrets, with redacted rendering |
| `ExportArtifact` | filename, MIME type, bytes, free-form provenance metadata |
| `ExportDeliveryResult` | the remote object id + a human-readable summary |
| `ExportDeliveryException` | failure with a **sanitized** message (no tokens, no key material, no response bodies) |
| `ExportDestinationRegistry` | the `id → destination` map, wired as a Spring `<map>` like the job handlers |
| `ExportDestinationConfigStore` | admin-side persistence under `${saiku.home}/export-destinations` |

The half that produces the bytes is separate, in `org.saiku.service.schedule.delivery`:

| Type | Role |
|---|---|
| `ExportArtifactProducer` | the "what" — produces an `ExportArtifact`. One producer today: `SavedQueryCsvArtifactProducer` |
| `ExportDeliveryJobHandler` | the `EXPORT_DELIVERY` job: produce → deliver |

## Enabling Google Drive

### 1. Create the service account

1. In Google Cloud, create a project, enable the **Google Drive API**, and create a **service
   account**.
2. Download its JSON key. **Store it outside `saiku-home`** — `saiku-home` is the directory operators
   back up and copy between environments, and this key should not travel with it.
3. Share the target Drive folder with the service account's `client_email` (Folder → Share → add
   person). Copy the folder id out of the folder URL (`https://drive.google.com/drive/folders/<id>`).

### 2. Save the config

```bash
curl -u admin:admin -X POST \
  http://localhost:8080/rest/saiku/admin/export-destinations/GOOGLE_DRIVE \
  -H 'Content-Type: application/json' \
  -d '{
        "settings": { "serviceAccountKeyFile": "/etc/saiku/drive-service-account.json",
                      "folderId": "1AbCdEfGhIjKlMnOp" }
      }'
```

Note there is no `secrets` block for Drive: this connector stores a **path** to your key file, never
the key material, so Saiku never holds the credential itself. A connector that does have to hold a
secret (an OAuth refresh token, say) declares it with
`ExportDestinationConfigField.secret(...)` and the API accepts it under `secrets` — which is
**write-only**: no read path in the API ever returns a credential value, only the
*names* of the secrets that are set. Omitting `secrets` on a later `POST` leaves the stored
credential untouched, so you can edit a folder id without re-pasting the key path.

Verify connectivity with the constant, server-generated probe (it never exports your data):

```bash
curl -u admin:admin -X POST \
  http://localhost:8080/rest/saiku/admin/export-destinations/GOOGLE_DRIVE/test
```

### 3. Schedule a delivery

Create an `EXPORT_DELIVERY` job via `POST /rest/saiku/admin/jobs`:

```bash
curl -u admin:admin -X POST http://localhost:8080/rest/saiku/admin/jobs \
  -H 'Content-Type: application/json' \
  -d '{
        "type": "EXPORT_DELIVERY",
        "enabled": true,
        "schedule": { "kind": "FIXED_INTERVAL", "intervalMillis": 604800000 },
        "payload": {
          "destination": "GOOGLE_DRIVE",
          "source": { "type": "SAVED_QUERY_CSV", "savedQuery": "/sales/q1.sai" }
        }
      }'
```

The owner is **server-minted** from the request `SecurityContext` (never from the body), and the query
runs **as that owner** — the owner-identity `JobRunner` establishes the `SecurityContext` and the
handler does not re-impersonate — so row-level security is exactly the owner's.

## Security model

### A job payload never contains a credential

The payload names a *destination id*; `ExportDestinationConfigStore` resolves the credentials at
delivery time. A job file in `saiku-home/jobs/` is therefore safe to diff, share and back up. This is
the property that will also make an operator-trusted `saiku-home/plugins/*.jar` destination safe when
that lands.

### Where config is stored

```
${saiku.home}/export-destinations/
├── GOOGLE_DRIVE.json     # plain settings only (folder id, key path) — safe to read back
└── secrets.json          # EVERY destination's credentials, mode 0600, namespaced by destination id
```

For Drive, `secrets.json` is empty — the key stays in the operator's file. A secret from any
connector never lands in a per-destination settings file, a job payload, a log line
(`ExportDestinationConfig.toString()` redacts), or an API response. Config values are validated on
save **and again at delivery time** — a key file that has been moved or `chmod`-ed away fails the run
loudly instead of silently mis-delivering.

### Drive threat model (why a service account, and why `drive.file`)

The issue asks us to pick one OAuth flow and document the threat model. We picked the
**service-account JWT-bearer grant** (installed-app / refresh-token flow is future work).

A Google service-account key is a long-lived, non-expiring credential. What bounds it:

| Concern | Mitigation |
|---|---|
| **Least privilege** | Only `https://www.googleapis.com/auth/drive.file` is requested. It can create files and read/delete **only files this service account created** — it cannot enumerate or read the operator's existing Drive. A leaked export is not also a leaked Drive. |
| **Blast radius is one folder** | Every upload carries `parents: [<folderId>]`. The key cannot write anywhere else, so key compromise is contained to one folder. |
| **Rotation** | Revoke by deleting the key in Google Cloud IAM. There is no stored token to invalidate and no Saiku-side session. |
| **Trust boundary** | The key file is admin-supplied and lives outside `saiku-home`; Saiku stores its *path* and reads the file at delivery time. Key material is never copied into Saiku's own store. |
| **No `google-api-client`** | The Drive call and the token mint are JDK-only (`java.net.http` + `java.security`). A transitive dependency tree is not a fair price for two HTTP calls, and it would land in the OWASP dependency-check scope. |

**Accepted risk:** an admin who sets this up grants a long-lived credential to the Saiku host. We
accept that in exchange for *no interactive OAuth round-trip*. A refresh-token flow would need a
browser consent screen and would put a long-lived **user** token on the host instead — a strictly
larger blast radius (it sees the operator's whole Drive) for the same job.

### Other hardening in the code

- **Artifact filenames are validated at the producer**: no path separator, no `..`, no control
  character, no leading dot, ≤ 200 chars. A crafted saved-query name cannot escape a remote folder.
- **The Drive folder id is pattern-checked** before it is interpolated into the metadata JSON, so a
  quote or bracket cannot break out of the JSON.
- **The artifact's content type is sanitised** before it goes into the multipart body, so a CRLF in a
  job-payload value cannot inject extra MIME headers.
- **Every remote failure is mapped to a status/reason**, never the response body — Google's error
  bodies and the `Authorization` header both stay out of the log.
- **CSV cells that start with `=`, `+`, `-` or `@` are single-quote prefixed.** An export routinely
  opens in a spreadsheet, and an unescaped cell value is a formula-injection vector into whoever opens
  the file. A legitimate `-42` is defused too: that is a cosmetic cost, and the alternative is a
  hole.

## Writing a Java destination

1. Implement `ExportDestination`:

   ```java
   public final class MyS3Destination implements ExportDestination {
       @Override public String id() { return "S3"; }
       @Override public String displayName() { return "Amazon S3"; }
       @Override public List<ExportDestinationConfigField> configFields() {
           return List.of(ExportDestinationConfigField.required("bucket", "Bucket"),
                          ExportDestinationConfigField.secret("accessKeyId", "Access key id"),
                          ExportDestinationConfigField.secret("secretAccessKey", "Secret access key"));
       }
       @Override public void validateConfig(ExportDestinationConfig config) throws ExportDeliveryException {
           config.require("bucket");          // names the field, never echoes a value
       }
       @Override public ExportDeliveryResult deliver(ExportArtifact a, ExportDestinationConfig c)
               throws ExportDeliveryException {
           // ... upload a.content() ...
           return ExportDeliveryResult.of(id(), objectKey, "uploaded " + a.filename());
       }
   }
   ```

   Read credentials with `config.secret(name)` and settings with `config.get(name)` / `config.require(name)`.
   Throw `ExportDeliveryException` (or its `remote` / `misconfigured` / `unavailable` factories) with a
   **sanitized** message.

2. Register the bean in `saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml`:

   ```xml
   <bean id="myS3Destination" class="com.example.MyS3Destination"/>

   <bean id="exportDestinationRegistry" class="org.saiku.service.export.destination.ExportDestinationRegistry">
     <property name="destinations">
       <map><entry key="S3" value-ref="myS3Destination"/></map>
     </property>
   </bean>
   ```

   The map key must be `UPPER_SNAKE_CASE` and must equal the destination's own `id()`; a mismatch is
   rejected at startup rather than becoming a usable alias.

3. That's it. `GET /saiku/admin/export-destinations` now lists your destination with a generated
   config form, and any `EXPORT_DELIVERY` job can name `"destination": "S3"`.

### The rules, in one list

- **No secret in a payload.** Credentials come from `ExportDestinationConfigStore`, always.
- **No secret in a message.** Every `ExportDeliveryException` message is sanitized — no token, no
  private key, no `Authorization` header, no verbatim remote response body.
- **Thread-safe.** `deliver()` runs on a scheduler worker thread *and* on an admin request thread
  (the connectivity probe). Never touch a request- or session-scoped bean; build what you need per
  call, as `SavedQueryCsvArtifactProducer` does.
- **Validate offline.** `validateConfig()` runs on the admin request thread and must not require the
  remote to be up.
- **Offline-first for the query.** The artifact producer runs **off-request** and must not depend on
  the session-scoped `thinQueryBean` (its scoped proxy throws `No thread-bound request found` on a
  scheduler thread). Build a fresh `ThinQueryService` from the same *singleton* collaborators
   `saiku-beans.xml` wires into `thinQueryBean` — that is what
   `SavedQueryCsvArtifactProducer` does.

## Not in this issue

Deferred on purpose, per the issue's MVP/later split:

- **Drop-in JAR discovery** for third-party destinations (`ServiceLoader` over
  `saiku-home/plugins/*.jar`). The bean-registration contract above is what such a loader would feed;
  the loader itself is later work.
- **OneDrive / S3 / Dropbox** — each is a new class against this SPI.
- **On-demand "Send to Drive"** from the workspace/dashboard toolbar. When it lands it must go through
  the same owner-scoped execution path as the scheduled job; the scheduled path is the MVP.
- **A signed plugin marketplace** — explicitly out of scope.
- **A refresh-token / installed-app OAuth flow** for Drive, if an operator prefers it over the
  service account.

## API

Admin-only (`ROLE_ADMIN` + the `/rest/saiku/admin/**` URL gate), at `/saiku/admin/export-destinations`:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | every registered destination, its config fields, whether it is configured, and the **names** of the secrets that are set |
| `GET` | `/{id}` | one destination, same shape |
| `POST` | `/{id}` | create/replace config; `{"settings":{…},"secrets":{…}}` |
| `DELETE` | `/{id}` | forget settings **and** credentials |
| `POST` | `/{id}/test` | deliver a constant, server-generated probe file |

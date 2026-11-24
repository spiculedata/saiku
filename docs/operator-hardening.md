# Operator hardening knobs

Configuration reference for the guardrails an operator is expected to tune:
the SMTP relay SSRF gate on the admin mail wizard, the daily LLM cost
budget on the AI ask surface (both added under
[#1918](https://github.com/spiculedata/saiku/issues/1918)), and the OLAP query
guardrails (added under [#1914](https://github.com/spiculedata/saiku/issues/1914)).

For the PII annotation contract — which is schema metadata rather than
deployment configuration — see
[`schema-annotations.md`](schema-annotations.md#saikusemanticpii--the-pii-contract).

---

## SMTP relay host (`saiku.mail.smtp.*`)

The admin mail wizard (`POST /saiku/api/admin/mail-config`) is admin-only, but
on a multi-tenant deployment "admin" is a tenant administrator, and the wizard's
"send a test email" button dials whatever host the wizard was given. SMTP has no
URL, so there is no `https`-only rule to lean on, and the response distinguishes
connection-refused from timeout from a protocol banner — which is enough to
blind-map which internal host:port pairs are listening (your control-plane
database, a cloud metadata endpoint, a neighbouring tenant's service).

Every host a wizard save proposes is therefore put through the same
resolve-and-range gate that alert webhooks have used since
[#1098](https://github.com/spiculedata/saiku/issues/1098), plus an SMTP port
allowlist. The check runs **on save** and again **immediately before the
test-send dial**, because `/test` is the endpoint that actually opens the
socket — a config written by an older build, or seeded straight into the
encrypted store, would otherwise be a ready-made probe.

Refused:

- hosts that are not a bare hostname or IP literal (a scheme, path, `user@`,
  or a smuggled `:port` in the host field);
- obfuscated numeric IPv4 (`2130706433`, `0x7f000001`, `0177.0.0.1`, `127.1`);
- internal names — `localhost`, `*.localhost`, `*.local`,
  `metadata.google.internal`, and any dotless short name;
- hosts that resolve to loopback / link-local / site-local / any-local /
  multicast / CGNAT (`100.64/10`) / IPv6 unique-local — and a host with even
  ONE internal address in its answer is refused, which is what closes the
  split-horizon / DNS-rebinding case;
- hosts that do not resolve at all;
- ports outside the allowlist.

| Property | Default | Meaning |
|---|---|---|
| `saiku.mail.smtp.allowedPorts` | `25,465,587,2525` | Comma-separated SMTP ports. A malformed entry is ignored rather than fatal — a typo must not brick mail configuration. |
| `saiku.mail.smtp.allowedHosts` | *(empty)* | Comma-separated hosts cleared **before** resolution. A leading-dot entry is a suffix match, so `.corp.example.com` covers every relay in the estate. Waives the range check only — syntax and port rules still apply. |
| `saiku.mail.smtp.allowPrivateRange` | `false` | `true` waives *both* the internal-name heuristic and the address-range check, for deployments whose mail really is internal. Syntax, obfuscated-numeric and port rules still apply, so it is a scoped "mail is internal here" switch, not a blank cheque. |

**The recommended shape is an ops-managed relay.** Set `SAIKU_MAIL_SMTP_HOST` /
`SAIKU_MAIL_FROM` in the environment: `MailConfigResolver` then reports the
deployment as ops-managed and the in-app wizard goes read-only (a save returns
`409`), so the tenant admin never chooses the host at all. The escape hatches
above exist for on-prem deployments where the relay is legitimately an RFC-1918
address on a dotless internal name — refusing to configure mail there would be a
regression, not a hardening.

Client-facing refusals are a single uniform message
(`smtp host/port is not an allowed mail relay`) and never echo the host, the
resolution result or which check fired. That distinction is the oracle a scanner
wants; the detail goes to the server log, where the operator configuring mail
can read it.

---

## File-engine confinement (`saiku.jdbc.fileEngineConfinement`)

DuckDB and H2 are on the JDBC scheme allow-list, and both reach the host: DuckDB
through `read_text` / `read_csv` / `read_parquet` and, with `httpfs`, over HTTP
(SSRF to cloud metadata and internal services); H2 through a `file:` database
anywhere on disk. Datasource creation is admin-only, but the SQL workbench
reaches the same primitives as a non-admin, so
[#2320](https://github.com/spiculedata/saiku/issues/2320) confines them where
every connection path shares it: `JdbcUrlPolicy`. **On by default.**

- **DuckDB** (`jdbc:duckdb:`, also inside a `jdbc:mondrian:Jdbc=` wrapper and the
  Ossie warehouse URL) opens with `enable_external_access=false`,
  `allowed_directories=[<allowed roots>]` and `lock_configuration=true`. Local
  files under the allowed roots stay readable; other paths and all network access
  are refused, and `SET enable_external_access=true` fails because the
  configuration is locked.
- **H2** file databases (`jdbc:h2:<path>`, `file:`, `nio:`, `split:`, `zip:`) must
  live under an allowed root, or the URL is rejected. `mem:`, `tcp:` and `ssl:`
  URLs are not file URLs and are unaffected.
- **Allowed roots** are `${saiku.home}` plus `saiku.jdbc.allowedFileRoots`. The
  launcher always sets `saiku.home`. If neither is set, confinement **fails
  closed**: H2 file URLs are rejected with an error naming `saiku.home`, and
  DuckDB still gets `enable_external_access=false` and `lock_configuration=true`
  with no allowed directories, so it cannot read local files at all. Set
  `saiku.home` (or `saiku.jdbc.allowedFileRoots`), or opt out below.

| Property | Default | Meaning |
|---|---|---|
| `saiku.jdbc.fileEngineConfinement` | `true` | `false` opts out of all of the above. Use only if you must read DuckDB files over HTTP or keep H2 files outside the allowed roots and cannot list them. |
| `saiku.jdbc.allowedFileRoots` | *(empty)* | Extra directories (platform path separator) DuckDB may read and H2 may keep databases in, e.g. `/mnt/warehouse:/srv/data`. |
| `saiku.jdbc.duckdb.allowR2Uploads` | `false` | `true` leaves `jdbc:duckdb:r2://` upload URLs (saiku-cloud) unconfined, because they need `httpfs`. |

Not covered here: `http(s):` Mondrian catalogs (`SaikuVirtualFileHandler`),
SQLite / Derby / HSQLDB file paths, and `jdbc:quack:` (a remote DuckDB client).
See the follow-up on #2320.

---

## AI ask cost budget (`saiku.ai.budget.*`)

The AI endpoints that reach the LLM provider used to be capped by call frequency
only: 30 requests per minute per `(user, IP)`. Frequency is not cost. A chained
ask is one request and up to `maxSteps` provider round-trips, each re-sending
the whole cube schema and the accumulated tool transcript, and it holds a Jetty
request thread for the duration. Thirty of those a minute is a five-figure
month and a thread-pool stall, and no counter ever reached a ceiling because
nothing was counting tokens.

The daily budget is ONE singleton bean (`aiCostBudget` in `saiku-beans.xml`)
shared by every endpoint that reaches the provider: `/ai/ask` (sync, streaming,
chained and in-space), `/ai/ossie/ask`, `/ai/narrate-dashboard`,
`/ai/describe-query` and `/ai/explain`. saiku#2529 documents both halves of why
it needed to move there: as a per-request field of the resource bean its
counters were rebuilt empty on every request (no ceiling could ever trip), and
the four non-ask endpoints carried no budget at all.

Four independent ceilings sit on top of the per-minute limiter:

| Ceiling | Default | Property | Disable with |
|---|---|---|---|
| Provider calls per principal per UTC day | 500 | `saiku.ai.budget.maxCallsPerUserPerDay` | `0` |
| Tokens per principal per UTC day | 2,000,000 | `saiku.ai.budget.maxTokensPerUserPerDay` | `0` |
| Tokens per instance per UTC day | 20,000,000 | `saiku.ai.budget.maxTokensPerInstancePerDay` | `0` |
| Concurrent chained asks | 4 | `saiku.ai.budget.maxConcurrentChains` | `0` |

Notes on the semantics, because they matter when you tune them:

- **Tokens come from the provider's own `usage` field** on each turn, not from
  an estimate. A turn where the provider reports no usage is charged a nominal
  amount rather than nothing, so a provider that stops returning `usage` cannot
  make the surface free while still billing you.
- **Chains are counted per step, not per request.** The HTTP request is one
  unit; the extra provider round-trips are charged as extra calls, because
  counting only the request makes the most expensive operation on the surface
  look like the cheapest.
- **The gate is pre-flight, the charge is post-hoc.** A turn that starts just
  under a ceiling can overshoot it by that one turn's cost. That is the right
  trade against refusing legitimate work whose price isn't knowable in advance.
- **The day window resets at UTC midnight**, not 24h after first use. A rolling
  window lets a caller shift spend into a quieter hour and never hit the ceiling
  at the same wall-clock moment; a calendar day is also the number an operator
  can reason about ("yesterday cost this much").
- **Both rate limiting and the budget key on the authenticated principal**,
  never on `(user, IP)`. The principal is resolved from the Spring Security
  context first (`AiRequestPrincipal`); the older derivation via the request's
  `getRemoteUser()` read the unwrapped container request and came back null even
  for authenticated callers, which sent users behind one NAT egress to the
  shared IP bucket and left the budget principal null (fail-open). Including
  the IP in the key meant a caller who changed address — a new NAT egress, a
  VPN, a per-request source NAT — got a brand-new bucket and could run the
  per-minute allowance several times over. Unauthenticated traffic still buckets
  by IP, which is the only identity it has.
- **Embed AI guests do not touch the token owner's allowance** (saiku#2529,
  review of PR #2556). An AI embed calls the ask endpoint under the token
  owner's identity, so a naive keying would let one anonymous viewer of the
  widget (or a script on the embedding page) exhaust the owner's per-minute
  bucket and daily AI budget. The embed view resource stamps an embed-specific
  bucket on the request (`embed:token:<sha256 of the token>`, or
  `embed:grant:<sub|ip>` for a public-grant) and the limiter and budget use it
  first, so guests share the embed's own per-minute and daily bucket and the
  owner's interactive use is unaffected. The instance-level ceiling still
  bounds every guest together with all other callers.
- **A caller whose identity can't be derived is allowed through** (fail-open on
  a wiring gap, matching the existing rate limiter) but still consumes the
  shared instance token budget, so "unattributable" never means "free".
- **Known gap: `/ai/ask/dashboard` is charged on the call ceiling only.** It
  is a single provider turn, but `DashboardSpec` does not carry the provider's
  reported usage, so no tokens are recorded for it. The per-day call ceiling
  still bounds it; only the token ceilings under-count that one endpoint.
- **Known gap: `/ai/ossie/ask` and `/ai/explain` are charged unpriced.**
  Neither result object carries the provider's usage report, so each LLM turn
  on those endpoints is charged the nominal token amount rather than the real
  spend. The call ceiling and the per-minute limiter bound their frequency; the
  token ceilings under-count those two endpoints.
- **Multi-node deployments get per-node budgets.** The counters are in-memory,
  single-node and lazy, like the rate limiter they sit on. That is the right
  trade for a guardrail whose job is to bound the worst case, but it means N
  nodes allow roughly N× the ceiling. Put a shared limiter in front of the
  cluster if you need a hard global bound.

### The concurrency cap

A chain holds its request thread until the chain deadline and re-sends the schema
on every step, so unbounded concurrency is a thread-pool stall and a spend
multiplier at the same time. Beyond `saiku.ai.budget.maxConcurrentChains`
in-flight chains, `/ai/ask/chain/stream` returns `429` immediately rather than
queueing — queueing is what causes the stall. The permit is released on every
exit path, including a mid-chain client disconnect.

### Reading the refusals

`429` with a `degraded` AskResponse on the ask surfaces (sync/streaming/space),
a degraded narrative envelope on `/ai/narrate-dashboard`, and a `{status,
error, code}` error body with `code: RATE_LIMITED` on `/ai/explain`. The
per-user refusals say which of the two user ceilings was hit, because the user
can act on it. The instance-wide refusal does **not** — telling one tenant that
other tenants are spending is nobody's useful information, and "try later" is
the only actionable answer either way. No refusal carries a token count, so the
API can't be used to read the ceiling back out.

---

## Pass-through datasource credentials (`saiku.passthrough.retainCredential`)

Datasources with `security.type=passthrough` connect to the warehouse as the signed-in user, so Saiku keeps that
user's login password in memory for the life of their HTTP session. It is held as a `char[]` outside the shared
session map (it is never returned by `GET /rest/saiku/session`, never written to the on-disk session store), is
replaced on re-login, and is zeroed on logout and when the HTTP session expires. A heap dump of a running
instance can still contain the passwords of currently signed-in users, so treat dumps as sensitive.

If you use no pass-through datasources, stop retaining the password altogether
(`JAVA_OPTS` below is honoured for `-D` properties by the launcher in the Docker image,
dist scripts and plain `java -jar`; in the container it cannot carry JVM flags like `-Xmx`,
which go in `JDK_JAVA_OPTIONS` instead):

```bash
JAVA_OPTS="-Dsaiku.passthrough.retainCredential=false"
```

---

## Login lockout and running behind a reverse proxy (`saiku.auth.trustedProxyHops`)

Failed logins (form login, HTTP Basic, XMLA and the demo email gate share one limiter) are counted in three
independent budgets inside a 15-minute window, and any one of them blocks further attempts with `429`:

| Budget | Limit | Purpose |
|---|---|---|
| (client IP, username) | 5 | one user's typos do not lock out colleagues behind the same NAT or proxy |
| username, from any IP | 20 | still stops a distributed guessing attack on one account |
| client IP, any username | 50 | still stops one source enumerating many usernames |

The username and per-IP limits default to 20 and 50 and can be changed with system properties, for example
`JAVA_OPTS="-Dsaiku.auth.maxAttemptsPerUser=30 -Dsaiku.auth.maxAttemptsPerIp=100"`. A value that is not a positive
integer is ignored with a `WARN` and the default is used. The username budget can, by design, lock a named account
out from every address for the window, so do not set it far below the number of attempts an attacker could make
in 15 minutes unless you accept that.

A successful login clears only the (IP, username) counter. The username budget is left to expire, so one legitimate
login (or Basic auth on every request) cannot keep resetting it; the cost is that a named account can be locked out
from every address for the window. Basic credentials the limiter cannot attribute to a username fall under the stricter
5-per-address budget.

By default the client IP is the TCP peer and `X-Forwarded-For` is ignored, which is right when Saiku is exposed
directly. Behind a reverse proxy every user then shares the proxy's address, so the per-IP budgets are shared as
well. Tell Saiku how many trusted proxies are in front of it:

```bash
JAVA_OPTS="-Dsaiku.auth.trustedProxyHops=1"
```

`trustedProxyHops=n` means the last `n` hops are proxies you control, counting the immediate proxy (the TCP peer),
so one nginx is `1`. The client is the `n`-th entry from the **right** of `X-Forwarded-For`, taken over every
`X-Forwarded-For` header line joined in order (a proxy that adds its own line instead of appending is handled);
entries to its left are supplied by the caller and are never used. If there are fewer than `n` entries the peer
address is used. Setting `n` higher than the real number of proxies hands the choice of key to the caller, so set
it to the exact number. The audit log's `ip` field uses the same resolution. Examples:

- one proxy that appends the client address (nginx `proxy_add_x_forwarded_for`, most ingresses): `1`
- a CDN in front of a load balancer in front of Saiku, each appending: `2`

`saiku.auth.trustForwardedFor=true` is a deprecated alias for `saiku.auth.trustedProxyHops=1`. Never set either
when clients can reach Saiku without going through the proxy.

---

## Disabling and demoting users (admin console, SCIM)

Saiku has two user stores. The bundled authenticator reads `users.properties` (who can log in, with which
password and roles); the admin console and SCIM write the H2 `USERS` / `USER_ROLES` tables. For an account
that exists in **both**, the H2 row now wins for revocation:

- `enabled = false` (SCIM `active:false` or `DELETE`, which deactivates) refuses form login and HTTP Basic
  (`/rest/**`, `/xmla/**`) with `401`, and ends an existing session on its next request.
- Removing `ROLE_ADMIN` from the row removes admin authority from that user's next request (the admin
  endpoints answer `403`, `GET /rest/saiku/session` reports `isadmin:false`) without a re-login.
- Editing a user's roles or email no longer re-enables a deactivated account.

Changes are picked up within about two seconds (lookups are cached briefly).

The check reads the row, not just its presence, and a row the store **found** is always enforced —
whatever its role column contains. Duplicate entries are tolerated (and are the normal shape: every
console- or SCIM-created row stores `ROLE_USER` twice, because `JdbcUserDAO.insert` prepends the role
the caller already sent), so a deactivated leaver is refused even then. Before saiku#2431 a
duplicate made the row read as unparseable, the check fell back to the authenticator, and the
deactivation was silently ignored.

If the user store itself cannot be read (database down, query error), the check **fails open**: the
account is left to the authenticator rather than every login being refused by a store outage. The
failure is logged at **ERROR** on `org.saiku.service.user.UserService` with the exception, and the
last answer stays cached for the usual ~2s window. Watch for that line — repeated ERRORs mean
revocation is not being enforced and the store needs attention.

### Scope, re-enabling and audit

- **Roles covered.** Only the *admin* role is reconciled: removing `ROLE_ADMIN` from the row strips admin
  authority. Removing any other role (for example `ROLE_SQL_EXEC` or a data role) from the row changes
  nothing for a user whose authenticator still grants it; revoke those in `users.properties` / the
  directory.
- **Authentication types covered.** Only credential logins (form login and HTTP Basic) are checked. OAuth
  bearer tokens, SSO/pre-authenticated principals and share/embed principals are exempt and governed by
  their own provider.
- **Re-enabling.** The admin console has no control to re-enable a deactivated row. Re-enable through SCIM
  (`PATCH`/`PUT` with `active:true`), or set `USERS.ENABLED` back to `TRUE` in the H2 database.
- **Case-variant rows.** If two rows differ only by letter case, the account is denied when *any* of them is
  disabled, and the roles of all of them count.
- **Audit.** A refused request and a demotion each write a line to the `org.saiku.audit` logger
  (`account_refused`, `account_demoted`, with the user and path). A deactivated user who supplies the right
  password over Basic is still recorded as `login_success` by the authenticator, followed by `account_refused`.
- **Role changes are atomic.** A role rewrite is one transaction, so a concurrent request never sees a user
  momentarily without roles.

### Upgrading

A user who is an admin in `users.properties` but whose H2 row lacks `ROLE_ADMIN` **loses admin authority**
once this check is active (the H2 row wins). Before upgrading, list rows with no admin role for the people
who should be admins (`SELECT u.USERNAME FROM USERS u WHERE NOT EXISTS (SELECT 1 FROM USER_ROLES r WHERE
r.USER_ID = u.USER_ID AND r.ROLE = 'ROLE_ADMIN')`) and add `ROLE_ADMIN` in the admin console. **Recovery** if
you are locked out: insert the role directly, `INSERT INTO USER_ROLES(USER_ID, USERNAME, ROLE) SELECT USER_ID,
USERNAME, 'ROLE_ADMIN' FROM USERS WHERE USERNAME = '<you>'`, or delete your row so the authenticator alone governs
the account. The change is picked up within about two seconds.

What this does **not** do: an account that has no H2 row is governed by the authenticator alone;
**deleting** a row (`DELETE /rest/saiku/admin/users/{id}`) removes it from the managed store, so it does not
by itself stop that user logging in. To deprovision, deactivate (SCIM) and remove the entry from
`users.properties` (or the external directory). Passwords are still those of the authenticator.
**Renaming** a row has the same effect as deleting it: the old name no longer matches a managed row, so a
deactivated account that is deleted or renamed can log in again via the authenticator. Revoking login on
deletion needs a tombstone or a single user store, which is not implemented.

---

## OLAP query guardrails (`saiku.olap.*`)

Every execute path applies server-side limits so a single authenticated request
cannot run unbounded or exhaust the heap (CWE-400 / CWE-770). They are on by
default; set them in `saiku.properties` or with `-Dsaiku.olap.<key>=<value>`.

| Property | Default | Effect |
|---|---|---|
| `saiku.olap.query.timeout.seconds` | `300` | JDBC statement timeout on every OLAP statement. `0` disables it. A negative or unparseable value falls back to the default. |
| `saiku.olap.max.rows` | `100000` | Ceiling on drillthrough and export rows and on raw `DRILLTHROUGH` bodies. A client asking for more, or for "everything", gets this value. Not positive falls back to the default; hard maximum 10,000,000. |
| `saiku.olap.arrow.max.bytes` | `268435456` (256 MiB) | Byte budget of the Arrow allocators behind the cellset and drillthrough IPC writers. |

Mondrian's own deployment-wide backstops in `mondrian.properties` ship with
non-zero values too: `mondrian.rolap.queryTimeout=300`,
`mondrian.result.limit=1000000`, `mondrian.rolap.iterationLimit=100000000`.
Raise them only if a legitimate report needs more, and prefer raising the
`saiku.olap.*` ceilings above first.

The Docker image and `dist/run.sh` / `dist/run.bat` start the JVM with
`-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75`, so an out-of-memory
condition restarts the process instead of leaving it limping.

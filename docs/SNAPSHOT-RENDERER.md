# Headless dashboard snapshot renderer (saiku#1810)

A dashboard renders **server-side**, with **no browser attached**, to **PDF** (for scheduled
subscriptions, #943) and **PNG** (for channel-digest thumbnails, #1099). It exists so the
scheduler (#1809) has something to attach to an unattended email or webhook post.

Code: `saiku-core/saiku-service/src/main/java/org/saiku/service/snapshot/`
Wiring: `saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml` (`snapshotRenderService`)

## The SSRF guard

A renderer that fetches a URL is an SSRF primitive: whoever names the target chooses where the
server goes. So **the unit of work is not a URL.**

A `SnapshotReference` carries a **relative repository path** (never a host, scheme or authority),
the panels to snapshot, the **owner** the render runs as, and an **expiry**.
`SnapshotReferenceSigner` serialises it to

```
base64url(payload) + "." + base64url(HMAC-SHA256(hmacKey, payload))
```

where `hmacKey` is derived from the per-install key (`CryptoUtil.installKeyMaterial()`),
domain-separated with the label `saiku:snapshot-ref:v1` — the same pattern as `UnsubscribeTokens`
(#1811), so **no new key mechanism is invented**.

Only a token that verifies is ever rendered. Concretely:

- **No fetch target exists in the payload.** `dashboardPath` is forced to be a relative path ending
  `.saikudash`, with no scheme, authority, `@`, backslash, leading `/`, `..`/`.` segment, empty
  segment, control character or NUL. `https://169.254.169.254/latest/meta-data` **cannot be
  expressed**, so there is nothing to be off-origin. Validation runs on **both** sign and verify, so
  a reference that could never have been minted can never be rendered either.
- **The renderer issues no outbound request.** The dashboard is resolved locally from the repository
  path. There is no fetch, so there is no redirect to follow off-origin. `SnapshotRendererTest`
  asserts the package references no networking API, so a future change that reintroduces one fails
  the build.
- **The owner is inside the signed bytes** and `render()` additionally requires the caller's
  authenticated name to equal it. A reference minted for Alice cannot be replayed as Bob, and an
  anonymous render is refused — there is no RLS scope to inherit, so there is nothing safe to
  snapshot.
- **References expire.** A leaked token stops working rather than becoming a permanent read
  capability.
- **Failure is closed and uniform.** Every rejection is a fixed, reason-only message — the offending
  token, path or user is never echoed, so the error text is not a signature or path oracle. MAC
  comparison is constant-time (`MessageDigest.isEqual` over raw bytes).

`sign()` re-encodes **from the reference**, never from caller-supplied bytes, so unsigned fields
cannot be smuggled alongside a valid signature. A payload whose declared panel count disagrees with
its panel set is refused rather than rendered as a subset.

## Owner identity and RLS

Measure reads go through the caller's ambient `SecurityContext`; this code never establishes,
elevates or impersonates an identity. Under the scheduler the owner-identity `JobRunner` has already
established the job owner's context, so a snapshot carries exactly the rows that owner could see
interactively — no more (row-level security) and no fewer. The reads reuse the same off-request
`MeasureValueReader` seam the threshold-alert (#1098) and digest (#943) handlers use, which builds a
fresh `ThinQueryService` from singleton collaborators and never touches the session-scoped
`thinQueryBean`, so it is safe on a scheduler worker thread.

## Bounds

A scheduler thread is scarce and an unattended render is unwatched, so a render that misbehaves must
fail on its own. All three caps are enforced fail-closed (`SnapshotLimits`):

| Cap | Default | Behaviour on breach |
|---|---|---|
| `maxPanels` | 64 | `snapshot reference exceeds the panel limit` |
| `timeoutMillis` | 30 000 | worker cancelled, `snapshot render timed out` |
| `maxOutputBytes` | 8 MiB | `snapshot output exceeded the size limit` |

The whole read-and-encode runs on a bounded, fixed, daemon-threaded pool with a short queue and an
`AbortPolicy`, so saturation reports `snapshot renderer is at capacity` rather than queueing without
limit. A read failure is logged with its cause but reported as a fixed `snapshot render failed`, so a
connection string or OLAP error cannot reach a caller or a recorded job outcome.

## Formats

- **PDF** — `PdfSnapshotRenderer` via OpenPDF (the maintained `com.lowagie:itext` fork already used
  by `saiku-web`). A pure-Java writer using the built-in standard-14 Helvetica metrics, so it needs
  no font files and is deterministic across hosts.
- **PNG** — `PngSnapshotRenderer` via headless AWT (`BufferedImage` + `Graphics2D`) and AWT's
  *logical* fonts. Canvas height is clamped, and long text is ellipsised, so many panels degrade
  visually rather than producing an unbounded bitmap.

Layout is deliberately plain (heading, generated-at line, label/value table). This is a distribution
primitive, not a pixel-accurate reproduction of the SvelteKit dashboard: it composes the same
owner-scoped values the interactive dashboard would show.

### Fonts in the container

A headless JRE with no fontconfig and no font files (a bare `eclipse-temurin` JRE base) throws
`Fontconfig head is null` from inside `sun.awt` at the first `drawString()`. The **PDF path is
unaffected** (OpenPDF carries its own metrics); the PNG path is not. The Dockerfile therefore
installs `fontconfig` + `fonts-dejavu-core`, and `PngSnapshotRenderer` translates a fontless JVM into
a `SnapshotRenderException` naming that fix rather than an unexplained AWT stack trace. The PNG
assertions skip on a host with genuinely no fonts — CI's runner and the shipped image both have them.

## Consumers

`SnapshotRenderService` is wired and available but **not yet called by any handler**:
`DASHBOARD_DIGEST` (#943) remains the lean link-based version, and #1099 digests are unaffected.
Wiring it here means #943/#1099 need no further infrastructure, only a call.

## Verification

43 unit tests across three classes. The full reactor (`mvn verify`) could not be run in the
contributing environment — `org.saiku:saiku-query` resolves only from GitHub Packages and the
available token lacks the `read:packages` scope — so the package was compiled and its tests run
directly against a classpath assembled from the local repository. CI runs the full gate.

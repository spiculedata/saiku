# Self-hosting the Saiku Excel add-in

The Saiku Excel add-in (a task-pane add-in for Excel on Mac, Windows, and the web) is
**built into Saiku**. Any Saiku install serves it at `/addin/` — there is no separate
artifact to deploy. This guide covers prerequisites, sideloading it into Excel (the
short-term path), and centralized deployment via the Microsoft 365 admin center (the
next step).

## Prerequisites

- **Serve Saiku over HTTPS.** Office only loads task-pane add-ins from `https://`. Use a
  real certificate (a reverse proxy such as nginx/Caddy/Traefik terminating TLS in front
  of the Saiku launcher or WAR is the usual setup).
- **Behind a reverse proxy, forward the public host/scheme.** The manifest is generated
  per-request from the public origin. Make the proxy send `X-Forwarded-Host` and
  `X-Forwarded-Proto` (most do by default). If you can't, set the public URL explicitly:
  ```
  -Dsaiku.addin.publicUrl=https://saiku.example.com
  ```
  (JVM system property, or the `saiku.addin.publicUrl` context-param.)

## Where it lives

- App: `https://<your-saiku-host>/addin/`
- Manifest: `https://<your-saiku-host>/addin/manifest.xml` — already filled in with **your**
  host (`SourceLocation`, `AppDomains`, icons). You never edit it.

## Sideloading into Excel (short term)

### macOS
1. Download your manifest: `curl -O https://<your-saiku-host>/addin/manifest.xml`
2. Copy it into Excel's sideload folder:
   ```
   ~/Library/Containers/com.microsoft.Excel/Data/Documents/wef/
   ```
3. Fully quit Excel (⌘Q) and reopen it.
4. **Insert → Add-ins** (or **My Add-ins ▾**) → **Developer Add-ins** → **Saiku for Excel**.

### Windows / Excel on the web
1. **Insert → Add-ins → Upload My Add-in.**
2. Point it at the manifest (file or the `https://<your-saiku-host>/addin/manifest.xml` URL).
3. The pane opens.

### Verify it works
- The task pane loads with the Saiku header.
- Sign in (your Saiku credentials) — the cube list loads.
- Build a query and **Insert** — a table is written to the sheet.

## Hardened deployments (framing)

The add-in must be frameable by Office. Saiku exempts `/addin/*` responses from
`X-Frame-Options` and serves an Office-scoped CSP:
```
Content-Security-Policy: frame-ancestors 'self' https://*.officeapps.live.com
  https://*.office.com https://*.office365.com https://*.microsoft.com https://*.sharepoint.com
```
So even if you harden the rest of Saiku with `-Dsaiku.security.frameAncestors=none`
(which sends `X-Frame-Options: DENY` everywhere else), **Office-on-the-web still loads the
pane**. No action needed — this is automatic.

## Centralized deployment via Microsoft 365 admin center (next step)

For org-wide rollout (no per-user sideload):
1. Microsoft 365 admin center → **Settings → Integrated apps → Upload custom apps**
   (or **Deploy Add-in**).
2. Provide the manifest URL: `https://<your-saiku-host>/addin/manifest.xml`.
3. Assign to users/groups.
4. The add-in appears automatically in those users' Excel (Mac, Windows, web).

The served manifest already meets centralized-deployment requirements (valid icons,
support URL, version ≥ 1.0.0.0, stable app ID).

## Not available (yet)

Public **AppSource** distribution is out of scope for self-hosting; it's a future option
for the hosted edition and requires Microsoft validation.

## Build note

The add-in is compiled and bundled during the Saiku build (`saiku-webapp` runs the add-in's
Vite build and packages `/addin/` into the WAR / launcher fat-jar). Backend-only builds can
skip it with `-P skip-addin` (then `/addin/` is absent — don't use that profile for releases).

# Embed Quickstart — a Saiku chart on your page in 5 minutes

This page takes you from "I want to embed a chart" to "the chart is
rendering" with copy-paste snippets. The full reference — every attribute,
event, `kind`, and the security model — lives in the
[`<saiku-embed>` component README](../../saiku-ui/src/embed/README.md).

Throughout, `https://YOUR-SAIKU.example.com` is your Saiku launcher origin
(locally: `http://localhost:8080`), and the examples use the FoodMart sample
content every fresh install ships with.

## 1. Install (30 seconds)

Add one script tag — the bundle is served by your Saiku server itself:

```html
<script src="https://YOUR-SAIKU.example.com/ui/saiku-embed.js"></script>
```

Or, for React / Vue / SPA projects that bundle their own JS:

```bash
npm install @concepttocloud/saiku-embed
```

```ts
import "@concepttocloud/saiku-embed"; // registers the <saiku-embed> tag globally
```

It's a real Custom Element (not an iframe): the same tag works in vanilla
HTML, React, Vue, and Svelte, and its internals are CSS-isolated in a shadow
root.

> **Subresource integrity:** the script is first-party — served by your own
> Saiku server, not a third-party CDN — and its content changes with every
> Saiku upgrade, so this page can't pin an `integrity="sha384-…"` hash for
> you. If your policy requires SRI on all external scripts, either compute
> the hash for your deployed version (`openssl dgst -sha384 -binary
> saiku-embed.js | openssl base64 -A`) and re-pin it on each upgrade, or use
> the npm route below — your bundler + lockfile then provide the integrity
> guarantee.

## 2. Mint a share token (1 minute)

Embeds authenticate with a server-minted, per-resource token — the host page
never handles Saiku credentials. Authenticated as a user with GRANT on the
saved query:

```bash
curl -X POST 'https://YOUR-SAIKU.example.com/rest/saiku/api/embed/tokens' \
  -u admin:admin \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceKind": "query",
    "resourcePath": "homes/admin/FoodMartTrend.saiku",
    "ttlHours": 72,
    "label": "Quickstart test"
  }'
# → { "status": "OK", "token": "tx-...", "expiresAt": ... }
```

Keep the `token` value — that's the only thing the host page needs. Tokens
are server-authoritative (opaque ids, no embedded claims): revoke any time
with `DELETE /rest/saiku/api/embed/tokens/<token>` and it dies on the next
request.

## 3. Drop the tag (30 seconds)

```html
<saiku-embed
  server="https://YOUR-SAIKU.example.com"
  token="tx-..."
  path="homes/admin/FoodMartTrend.saiku"
  render="chart"
  mode="line"
  height="400px"
></saiku-embed>
```

That's it — the query executes server-side under the token's scope and the
chart renders. Swap `render` for `table`, `matrix`, or `kpi`, or `mode` for
`bar` / `pie`. Dashboards embed the same way with `kind="dashboard"` and a
`.saikudash` path; a plain-English AI ask box is `kind="ai"` with a cube ref
path (see the [README](../../saiku-ui/src/embed/README.md#use) for every
shape).

## 4. Theme it

Quick palette: set `theme="dark"` (or `theme="auto"` to follow the viewer's
`prefers-color-scheme`). Fine-tuning: CSS variables on the host page — the
shadow root keeps everything else isolated:

```css
saiku-embed {
  --saiku-embed-fg: #0f172a;
  --saiku-embed-bg: transparent;
  --saiku-embed-border: #cbd5e1;
  --saiku-embed-header-bg: #f1f5f9;
  --saiku-embed-tile-bg: #ffffff;
}
```

## 5. Auth options, from simplest up

1. **Share token (start here)** — what you minted in step 2. One token pins
   exactly one query / dashboard / cube / app; it travels as the
   `X-Saiku-Embed-Token` header (never a query parameter, so it can't leak
   into logs or `Referer`). Any row-level-security filters attached to the
   token at mint time are applied server-side and **fail closed**; PII
   redaction likewise. See the README's
   [security model](../../saiku-ui/src/embed/README.md#security-model).
2. **Public grant (no token at all)** — for a public blog post, mark the
   resource publicly embeddable server-side and omit `token`. See
   [public grants](../../saiku-ui/src/embed/README.md#server-side-public-grants).

## Common gotchas

- **CSP on the host page.** The host's Content-Security-Policy needs
  `script-src` to allow the Saiku origin (for `saiku-embed.js`, unless you
  npm-bundle) and `connect-src` to allow it (for the data fetches). If the
  embed renders nothing and the console shows CSP violations, this is it.
- **Cross-origin is the normal case, not a special one.** The component
  fetches with `credentials: "omit"` — the token is the only auth carrier, so
  no cookie/CORS-credential complications. Just make sure the Saiku origin is
  reachable from the *viewer's browser* (not merely from your server).
- **Web component, not an iframe.** There's no `X-Frame-Options` /
  `frame-ancestors` dance and host CSS can't bleed in (shadow root). If you
  specifically need iframe-style process isolation, wrap the tag in your own
  iframe page.
- **Blank chart but a token you believe in?** Every failure mode (wrong
  path, wrong kind, expired, revoked) returns the same opaque `EMBED_INVALID`
  401 by design — probes can't enumerate. Re-mint the token for the exact
  resource path + kind you're embedding.
- **Same-origin pages can omit `server`** (v3.19+) — if the host page is
  served by Saiku itself, leave the attribute off.

## Current limitations (v1)

Records-format rendering only; dashboard filter tiles render as authored
(no interactive filter bar); `text` tile markdown renders as plain text. The
full list lives in the
[README](../../saiku-ui/src/embed/README.md#limitations).

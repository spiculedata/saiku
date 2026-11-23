# Embed Quickstart — a Saiku chart on your page in 5 minutes

This page takes you from "I want to embed a chart" to "the chart is
rendering" with copy-paste snippets. The full reference — every attribute,
event, `kind`, and the security model — lives in the
[`<saiku-embed>` component README](../../saiku-ui/src/embed/README.md).

Throughout, `https://YOUR-SAIKU.example.com` is your Saiku launcher origin
(locally: `http://localhost:8080`), and the examples use the FoodMart sample
content every fresh install ships with.

## 1. Install (30 seconds)

Add one script tag — the bundle is served by your Saiku server itself. For
just a chart, the narrower `<saiku-chart>` tag (issue #1103) is a smaller
download than the full `<saiku-embed>` bundle:

```html
<script src="https://YOUR-SAIKU.example.com/ui/saiku-chart.js"></script>
```

Embedding a whole dashboard instead? Use `<saiku-dashboard>`:

```html
<script src="https://YOUR-SAIKU.example.com/ui/saiku-dashboard.js"></script>
```

Or, for everything — table/matrix/kpi rendering, App Builder apps, the AI
ask widget — `<saiku-embed>`:

```html
<script src="https://YOUR-SAIKU.example.com/ui/saiku-embed.js"></script>
```

Or, for React / Vue / SPA projects that bundle their own JS:

```bash
npm install @concepttocloud/saiku-embed
```

```ts
import "@concepttocloud/saiku-embed"; // registers <saiku-embed>
import "@concepttocloud/saiku-embed/chart"; // registers <saiku-chart>
import "@concepttocloud/saiku-embed/dashboard"; // registers <saiku-dashboard>
```

Each is a real Custom Element (not an iframe): the same tags work in vanilla
HTML, React, Vue, and Svelte, and their internals are CSS-isolated in a
shadow root. The rest of this quickstart uses `<saiku-embed render="chart">`
for the copy-paste snippets since it also covers table/matrix/kpi — swap in
`<saiku-chart>` (dropping `render`/`kind`) if that's all you need; the
attributes that carry over (`server`, `token`, `path`, `mode`, `height`,
`filter`, `theme`) are identical.

> **Subresource integrity:** the script is first-party — served by your own
> Saiku server, not a third-party CDN — and its content changes with every
> Saiku upgrade, so this page can't pin an `integrity="sha384-…"` hash for
> you. If your policy requires SRI on all external scripts, either compute
> the hash for your deployed version (`openssl dgst -sha384 -binary
saiku-embed.js | openssl base64 -A`) and re-pin it on each upgrade, or use
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
chart renders. (Equivalently: `<saiku-chart server="..." token="tx-..."
path="homes/admin/FoodMartTrend.saiku" mode="line" height="400px">`.) Swap
`render` for `table`, `matrix`, or `kpi`, or `mode` for `bar` / `pie`.
Dashboards embed the same way with `kind="dashboard"` and a `.saikudash`
path (or the dedicated `<saiku-dashboard>` tag); a plain-English AI ask box is `kind="ai"` with a cube ref
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

## Cross-origin setup (CORS)

If the host page is not served from the Saiku origin you have two safe options.

**Option A, preferred: make it same-origin.** Proxy a path on your own site to
Saiku (for example `https://wiki.example.com/saiku/` to the Saiku server) and
point `server` at that path. No CORS is needed and nothing below applies.

**Option B: scoped CORS on the guest embed prefixes only.** The embed read
endpoints are bearer-token endpoints, so they can be made cross-origin readable
without exposing anything cookie-authenticated. Apply CORS to these paths and
nothing else:

- `/rest/saiku/api/embed/query/`
- `/rest/saiku/api/embed/dashboard/`
- `/rest/saiku/api/embed/app/`

with this response policy (never enable `Access-Control-Allow-Credentials`):

| Header | Value |
|---|---|
| `Access-Control-Allow-Origin` | one explicit origin per response, taken from a fixed allowlist (for example `https://wiki.example.com`) |
| `Access-Control-Allow-Headers` | `X-Saiku-Embed-Token` (plus `Content-Type` if you use app/AI posts) |
| `Access-Control-Allow-Methods` | `GET, POST, OPTIONS` (only what the kinds you embed need) |
| `Vary` | `Origin` |
| `Access-Control-Allow-Credentials` | **never set it** |

Nginx sketch (allowlist via `map`, so the `Origin` header is only echoed when it
is one of your hosts):

```nginx
map $http_origin $saiku_embed_origin {
    default                      "";
    "https://wiki.example.com"   $http_origin;
}

location ~ ^/rest/saiku/api/embed/(query|dashboard|app)/ {
    add_header Access-Control-Allow-Origin  $saiku_embed_origin always;
    add_header Access-Control-Allow-Headers "X-Saiku-Embed-Token, Content-Type" always;
    add_header Access-Control-Allow-Methods "GET, POST, OPTIONS" always;
    add_header Vary Origin always;
    if ($request_method = OPTIONS) { return 204; }
    proxy_pass http://saiku;
}
```

> **Warning: do not reflect the request `Origin` and enable credentials on
> `/rest/**`.** The copy-paste CORS recipe (`Access-Control-Allow-Origin:
> $http_origin` together with `Access-Control-Allow-Credentials: true`, applied
> to the whole REST API) lets any website make credentialed requests to Saiku
> and read the responses. Saiku's CSRF protection does not check `GET`, so a
> visitor's session would let that site read the repository, saved queries and
> query results, and the `X-XSRF-TOKEN` header becomes addable from any origin.
> Saiku's CSRF model relies on Saiku never granting cross-origin credentialed
> access. Likewise never set the session cookie to `SameSite=None` just to make
> embedding work: the embed does not use the session cookie at all. Never
> allow-list `*` or `null` together with credentials either.

## Common gotchas

- **CSP on the host page.** The host's Content-Security-Policy needs
  `script-src` to allow the Saiku origin (for `saiku-embed.js`, unless you
  npm-bundle) and `connect-src` to allow it (for the data fetches). If the
  embed renders nothing and the console shows CSP violations, this is it.
- **Cross-origin needs CORS, and Saiku does not ship any.** The component
  fetches with `credentials: "omit"`, but it sends the token in the custom
  `X-Saiku-Embed-Token` header, so the browser sends a CORS **preflight**
  (`OPTIONS`) first. Saiku does not ship any CORS configuration (it answers no `Access-Control-*` headers), so on a
  host page served from another origin the embed renders nothing until you add
  CORS in front of Saiku (reverse proxy, ingress or servlet filter). Do it
  narrowly; see [Cross-origin setup (CORS)](#cross-origin-setup-cors) below.
  Also make sure the Saiku origin is reachable from the *viewer's browser*
  (not merely from your server).
- **Web component, not an iframe.** Host CSS can't bleed in (shadow root), and
  you don't need to widen Saiku's own framing headers — the web component
  fetches data over XHR. If you specifically need iframe-style process
  isolation, wrap the tag in your own iframe page; note that since saiku#1917
  `/ui/**` ships `frame-ancestors 'self'`, so a cross-origin iframe of Saiku
  itself needs the deployment to set
  `-Dsaiku.security.frameAncestors="'self' https://wiki.example.com"`.
- **Blank chart but a token you believe in?** Every failure mode (wrong
  path, wrong kind, expired, revoked) returns the same opaque `EMBED_INVALID`
  401 by design — probes can't enumerate. Re-mint the token for the exact
  resource path + kind you're embedding.
- **Same-origin pages can omit `server`** (v3.19+) — if the host page is
  served by Saiku itself, leave the attribute off.

## See also

- [`creator-mode.md`](creator-mode.md) — **Creator Mode** (`kind="creator"`): let
  your customers build their own dashboards against a cube you pin, without
  leaving your product. The one embed kind that writes; the guide covers
  per-tenant tokens, the threat model and the endpoint reference.

## Current limitations (v1)

Records-format rendering only; dashboard filter tiles render as authored
(no interactive filter bar); `text` tile markdown renders as plain text. The
full list lives in the
[README](../../saiku-ui/src/embed/README.md#limitations).

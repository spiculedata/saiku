# `<saiku-embed>` — Saiku Web Component

Drop a saved Saiku query or dashboard into any page on the open web. Same
tag works in React, Vue, Svelte, and vanilla HTML — it's a real
[Custom Element](https://developer.mozilla.org/docs/Web/API/Web_components),
so the host page doesn't have to know anything about Saiku internals.

## Install

```html
<script src="https://YOUR-SAIKU.example.com/ui/saiku-embed.js"></script>
```

Or via npm (for React / Vue / SPA projects that bundle their own JS):

```bash
npm install @saikuanalytics/embed
```

```ts
import "@saikuanalytics/embed";
```

The import has the side effect of registering the `saiku-embed` tag
globally — no further setup.

## Use

### A saved query, rendered as a table

```html
<saiku-embed
  server="https://YOUR-SAIKU.example.com"
  token="..."
  path="homes/admin/Examples/Trend.saiku"
  height="400px"
></saiku-embed>
```

### A saved query, rendered as a bar / line / pie chart

```html
<saiku-embed
  server="..."
  token="..."
  path="homes/admin/Examples/Sales.saiku"
  render="chart"
  mode="bar"
  height="500px"
></saiku-embed>
```

### A saved dashboard

```html
<saiku-embed
  server="..."
  token="..."
  kind="dashboard"
  path="homes/admin/exec.saikudash"
  height="700px"
></saiku-embed>
```

### Anonymous public embed

If the resource is marked publicly embeddable on the server
(see "Public grants" below), omit the token entirely:

```html
<saiku-embed
  server="..."
  path="shared/public-chart.saiku"
  render="chart"
></saiku-embed>
```

## Attributes

| Attribute | Default     | Notes                                                                  |
|-----------|-------------|------------------------------------------------------------------------|
| `server`  | _(required)_| Origin of the Saiku launcher, e.g. `https://demo.saiku.bi`            |
| `path`    | _(required)_| Repository path of the saved query (`.saiku`) or dashboard (`.saikudash`) |
| `kind`    | `query`     | `query` or `dashboard`                                                 |
| `token`   | _(none)_    | Embed token from `POST /saiku/api/embed/tokens`. Omit for public reads |
| `render`  | `table`     | For `kind=query`: `table` or `chart`                                  |
| `mode`    | `bar`       | For `render=chart`: `bar`, `line`, or `pie`                            |
| `height`  | `400px`     | CSS height of the rendered surface                                     |

The component re-renders whenever an attribute changes, so frameworks
binding state to attrs (React's JSX, Vue's `:server="..."`, etc.) just
work.

## Server-side: minting a token

Authenticated as a user who has GRANT on the saved query / dashboard:

```bash
curl -X POST 'https://YOUR-SAIKU/rest/saiku/api/embed/tokens' \
  -u admin:admin \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceKind": "query",
    "resourcePath": "homes/admin/Examples/Trend.saiku",
    "ttlHours": 72,
    "label": "Marketing landing page"
  }'
# → { "status": "OK", "token": "tx-...", "expiresAt": ... }
```

Paste the `token` into the host page's `<saiku-embed token="...">`.
Tokens are server-authoritative — revoke any time via:

```bash
curl -X DELETE 'https://YOUR-SAIKU/rest/saiku/api/embed/tokens/<token>' \
  -u admin:admin
```

## Server-side: public grants

To make a resource readable WITHOUT a token (e.g. for a public blog post):

```bash
curl -X POST 'https://YOUR-SAIKU/rest/saiku/api/embed/public' \
  -u admin:admin \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceKind": "query",
    "resourcePath": "shared/public-chart.saiku",
    "label": "Homepage chart"
  }'
```

Public reads still run under the grantor's data scope, so any
session-injected filters render from the grantor's perspective.
Revoke:

```bash
curl -X DELETE 'https://YOUR-SAIKU/rest/saiku/api/embed/public?kind=query&path=shared/public-chart.saiku' \
  -u admin:admin
```

## Styling

The embed lives inside an
[open shadow root](https://developer.mozilla.org/docs/Web/API/ShadowRoot),
so host page CSS can't leak in and vice versa. To recolour the embed,
set CSS variables on the host page:

```css
saiku-embed {
  --saiku-embed-fg: #0f172a;
  --saiku-embed-bg: transparent;
  --saiku-embed-border: #cbd5e1;
  --saiku-embed-header-bg: #f1f5f9;
  --saiku-embed-tile-bg: #ffffff;
  --saiku-embed-row-hover: #e2e8f0;
  --saiku-embed-negative: #b91c1c;
  --saiku-embed-error: #b91c1c;
  --saiku-embed-muted: #64748b;
}
```

## Security model

- **Header-only token transport.** The token travels as
  `X-Saiku-Embed-Token`. Never a `?token=` query parameter — those leak
  into access logs, proxy logs, browser history, and outbound
  `Referer`.
- **Server-side authoritative.** Tokens are opaque random 256-bit ids
  with no embedded claims; the server looks them up on every request.
  Revocation takes effect on the very next request.
- **Per-resource scope.** A token pins exactly one query or dashboard.
  Replaying it against any other resource (or any other endpoint)
  returns the same opaque `EMBED_INVALID` 401, regardless of whether
  the request used the wrong kind, the wrong path, an expired token,
  or a revoked one. Probes can't enumerate.
- **Cross-origin cookie isolation.** The embed sends
  `credentials: "omit"`, so the host page's Saiku session cookie (if
  the user happens to be logged in) doesn't flow with embed reads.
  The token IS the only auth carrier on this surface.

## Bundle size

Around **213 KB gzipped** at the time of writing — Svelte 5 custom
element runtime + ECharts (core + bar / line / pie + four common
components, modular tree-shaken) + the embed renderers.

## Limitations

- Records-format only. The matrix format isn't rendered in v1.
- Dashboard `filter` tiles are skipped — the embed renders the
  authored data as-is without an interactive filter bar.
- Markdown in `text` tiles renders as plain text (no `marked`
  dependency to keep the bundle tight).
- AI Query results (`/ai/query`) aren't wired as an `<saiku-embed>`
  source yet — coming in a follow-up.

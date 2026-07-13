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
npm install @concepttocloud/saiku-embed
```

```ts
import "@concepttocloud/saiku-embed";
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

### A saved query, rendered as a hierarchical matrix (v3.19)

Matrix mode preserves the row / column axis structure — measures on
columns, dimension members on rows — instead of flattening to a single
row-key map like `render="table"` does. Useful for pivot-style reports.

```html
<saiku-embed
  server="..."
  token="..."
  path="homes/admin/Examples/Sales.saiku"
  render="matrix"
  height="500px"
></saiku-embed>
```

### An AI ask widget over a cube (v3.19, `kind="ai"`)

Point the token at a cube (rather than a saved query) and drop in a
plain-English ask box. Behind the scenes it POSTs to
`/rest/saiku/api/embed/ai/{cubeId}/ask`, which runs the question through
the server's configured LLM provider under the pinned owner's data
scope. Requires an AI-kind token (see "Minting an AI token" below).

```html
<saiku-embed
  server="..."
  token="..."
  kind="ai"
  path="foodmart/FoodMart/FoodMart/Sales"
  height="200px"
></saiku-embed>
```

### A single KPI tile (v3.20, `render="kpi"`)

The most common embed shape — one governed figure rendered large. Derives its
value from the same records response as `render="table"`, so it needs no server
change. Point it at a saved query whose last measure is the headline number;
when the query carries a prior measure column, a delta chip is shown.

```html
<saiku-embed
  server="..."
  token="..."
  path="homes/admin/Examples/NetRevenue.saiku"
  render="kpi"
  height="160px"
></saiku-embed>
```

### A saved query, sliced at embed time (v3.20, `filter`)

Pass slicer overrides as a JSON array. They ride the same validated slicer path
the dashboard filter tiles use — the saved query's cube binding and axes are
untouched, so a host can parameterise an embed without re-authoring the query.

```html
<saiku-embed
  server="..."
  token="..."
  path="homes/admin/Examples/Sales.saiku"
  filter='[{"dimension":"Time","level":"Year","members":["[Time].[2024]"]}]'
></saiku-embed>
```

### A persona-scoped AI ask (v3.20, `space`)

Add a `space` to a `kind="ai"` embed to scope the assistant to an admin-authored
[Agent Space](../../../docs/AGENT-SPACES-SPEC.md) persona. The persona's system
prompt, skill filter, and cube allowlist apply server-side. The cube stays
pinned by the token, so a space can only **narrow** what the guest reaches — if
the space's allowlist excludes the pinned cube, the ask fails closed.

```html
<saiku-embed
  server="..."
  token="..."
  kind="ai"
  path="foodmart/FoodMart/FoodMart/Sales"
  space="foodmart-sales-analyst"
  height="240px"
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
| `server`  | _(optional)_| Origin of the Saiku launcher, e.g. `https://demo.saiku.bi`. Leave empty for same-origin (v3.19+) |
| `path`    | _(required)_| `kind=query`: saved query path (`.saiku`) — `kind=dashboard`: dashboard path (`.saikudash`) — `kind=ai`: cube ref `connection/catalog/schema/cubeName` |
| `kind`    | `query`     | `query`, `dashboard`, or `ai`                                          |
| `token`   | _(none)_    | Embed token from `POST /saiku/api/embed/tokens`. Omit for public reads |
| `render`  | `table`     | For `kind=query`: `table`, `matrix`, `chart`, or `kpi` (v3.20)         |
| `mode`    | `bar`       | For `render=chart`: `bar`, `line`, or `pie`                            |
| `height`  | `400px`     | CSS height of the rendered surface                                     |
| `space`   | _(none)_    | For `kind=ai`: Agent Space persona id — scopes the ask server-side (v3.20) |
| `filter`  | _(none)_    | For `kind=query`: JSON array of slicer overrides applied at embed time (v3.20) |
| `theme`   | _(light)_   | `light`, `dark`, or `auto` (follow `prefers-color-scheme`) (v3.20)     |

The component re-renders whenever an attribute changes, so frameworks
binding state to attrs (React's JSX, Vue's `:server="..."`, etc.) just
work.

## Events (v3.20)

The element emits namespaced `CustomEvent`s so the host page can react to what
happens inside the embed. All bubble and are `composed`, so a listener on the
`<saiku-embed>` element receives them:

| Event             | `detail`                          | Fires when                              |
|-------------------|-----------------------------------|-----------------------------------------|
| `saiku:load`      | `{ kind, rows }`                  | a query / matrix / kpi surface loads    |
| `saiku:error`     | `{ message }`                     | a query load fails (friendly message)   |
| `saiku:select`    | `{ row }`                         | a table row is clicked (`render=table`) |
| `saiku:ai-query`  | `{ question, degraded }`          | an AI ask resolves (`kind=ai`)          |

```js
const el = document.querySelector("saiku-embed");
el.addEventListener("saiku:load", (e) => console.log("loaded", e.detail.rows, "rows"));
el.addEventListener("saiku:select", (e) => showDetail(e.detail.row));
```

In React (via `@concepttocloud/saiku-embed-react`) the same events are exposed
as `onLoad` / `onError` / `onSelect` / `onAiQuery` callback props.

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

### Minting an AI token (v3.19)

For `kind="ai"` embeds. `resourcePath` is a cube ref rather than a
file path. Mint is admin-only for v1 (cube-level ACLs are a follow-up).

```bash
curl -X POST 'https://YOUR-SAIKU/rest/saiku/api/embed/tokens' \
  -u admin:admin \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceKind": "ai",
    "resourcePath": "foodmart/FoodMart/FoodMart/Sales",
    "ttlHours": 72,
    "label": "DimSum widget on marketing site"
  }'
```

Server-side, an AI ask requires the launcher to have an LLM provider
configured (`saiku.ai.ask.provider = anthropic | openai` plus the
matching API key). Without it, the widget renders a degraded message
rather than an error.

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
so host page CSS can't leak in and vice versa.

For a quick dark surface, set `theme="dark"` (or `theme="auto"` to follow the
viewer's `prefers-color-scheme`) — it swaps the whole palette without the host
having to set each variable (v3.20). Leaving `theme` unset keeps the original
light palette, so existing embeds are unchanged.

To fine-tune individual colours, set CSS variables on the host page:

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

### Theming the chart itself

The variables above style the embed **chrome** (frame, header, table). To brand
the **chart series + axes**, set these (custom properties inherit through the
shadow boundary, so the canvas chart picks them up):

```css
saiku-embed {
  /* Series colour cycle — set as many as you need, 1..8, contiguously.
     Any unset → the chart falls back to the built-in palette. */
  --saiku-embed-chart-1: #2563eb;
  --saiku-embed-chart-2: #16a34a;
  --saiku-embed-chart-3: #dc2626;
  /* …up to --saiku-embed-chart-8 */

  /* Axis labels / legend / titles use --saiku-embed-fg;
     axis + split lines use --saiku-embed-muted (both shared with the chrome). */
}
```

An embed with none of these set renders exactly as before (ECharts defaults).

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

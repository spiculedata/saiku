# MCP native endpoint — migration notes (issue #878)

The streamable-http MCP server is no longer a standalone `saiku-mcp` JAR
in front of `saiku-launcher` via mcp-proxy. It now lives **inside
saiku-webapp** at `/rest/saiku/api/mcp`, behind the same Spring Security
chain as `/rest/saiku/api/ai/*`. One auth model, one role-propagation
path, no separate process.

## What's gone

- `saiku-mcp/` module — deleted.
- `saiku-mcp-${VERSION}.jar` release asset.
- `docker/saiku-mcp` wrapper script.
- The `mcp` subcommand of `docker/saiku-entrypoint`.
- `SAIKU_URL` / `SAIKU_USER` / `SAIKU_PASS` defaults in the image — the
  baked-in admin creds path is gone. Per-user creds now flow from the
  DXT shim's `user_config`.

## Deployment migration

### demo.saiku.bi

```bash
sudo systemctl stop saiku-mcp-proxy.service
sudo systemctl disable saiku-mcp-proxy.service
```

Update Caddy to drop the `/mcp` reverse-proxy block. The native endpoint
is served by saiku-launcher at `/rest/saiku/api/mcp` on the same vhost,
so the path is reachable without any Caddy rewrite.

### Anyone running the demo image

```bash
docker pull ghcr.io/spiculedata/saiku:latest
docker stop saiku-demo && docker rm saiku-demo
docker run -d -p 8080:8080 --name saiku-demo \
  -e SAIKU_MCP_URL=https://your-host/rest/saiku/api/mcp \
  ghcr.io/spiculedata/saiku:latest
```

`SAIKU_MCP_URL` is now optional — when unset, `/rest/saiku/info/capabilities`
auto-derives the URL from the request's scheme + host (so localhost dev
and production both work without per-env config). Set it when the public
URL differs from the URL Jetty sees (TLS termination at a reverse proxy
with a different hostname).

## DXT bundle changes

The bundle generated at `/rest/saiku/info/mcp.dxt` now includes:

- `manifest.json` with a `user_config` block prompting for Saiku
  username + password at install time.
- `server.js` — a self-contained Node stdio↔streamable-http bridge
  that reads `SAIKU_USER` / `SAIKU_PASS` from its environment (populated
  by the host from `user_config`) and attaches
  `Authorization: Basic <base64>` to every request.

Users who installed an older DXT will be prompted for credentials again
when they upgrade — there are no creds baked into the bundle now.

## Audit-trail check

Every MCP call lands in the structured audit log under the user's real
identity (no more `mcp-proxy` shared service account). Spring Security's
`AuthenticationSuccessEvent` / `AbstractAuthenticationFailureEvent` is
bridged onto `LoginRateLimiter`, so a bad-creds storm on
`/rest/saiku/api/mcp` gets the same 5-per-15-min 429 treatment as the
REST endpoints.

## Mondrian role propagation

The AI services (`OlapDiscoverService`, `ThinQueryService`) acquire
olap4j connections via `IConnectionManager.getOlapConnection()` on every
call — no cached `Cube` references. `SecurityAwareConnectionManager`
re-applies `setRoleName()` on every hand-out keyed by
`datasource-username`, so users whose Saiku role lists FoodMart
restricted to USA see only USA rows from `run_query` regardless of
which agent issued the call. No code change needed — the path already
goes through the connection manager.

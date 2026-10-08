# Outbound MCP — DimSum calling external MCP servers (`saiku-home/mcp-servers/`)

Saiku has always been **inbound-MCP only**: [`/rest/saiku/api/mcp`](AI-QUERY-API.md)
exposes Saiku's own MDX + Ossie tools so external agents (Claude Desktop,
Cursor, mcp-inspector) can call *into* Saiku. Outbound MCP (saiku#1425)
is the other direction: DimSum, the ask layer's chained agentic loop,
can call *out* to admin-registered external MCP servers mid-conversation
— Notion for context, Linear for tickets, Sentry for errors, or any
custom internal HTTPS endpoint that speaks MCP.

The semantic layer stays authoritative. External MCP calls are context
an admin has explicitly opted into, never a replacement for a cube/model
query, and never override Agent Space cube/skill scoping.

## Shape

An outbound server registration bundles:

- **`url`** — the remote server's streamable-http MCP endpoint.
- **`authHeaderName` / `authHeaderValue`** — an optional single header
  credential (e.g. `Authorization: Bearer sk-...`), stored encrypted at
  rest with the same AES-256-GCM per-install key datasource passwords
  use (`CryptoUtil`). Never returned by the admin API once saved — only
  whether one is set.
- **`enabledTools`** — an explicit allowlist of tool names. **Default
  off**: a freshly registered server exposes NOTHING to the LLM until an
  admin discovers its tool catalogue and opts each tool in individually.
  This mirrors what shipped MCP-connector products in this space do
  (admin picks a connector, then enables specific tools per-connector,
  org-wide) and matches Saiku's existing Agent Spaces philosophy — an
  agent's reach can be *narrowed* by config, never silently *widened* by
  registering a server.

Registrations are persisted as JSON files under `saiku-home/mcp-servers/`.
The launcher scans lazily (mtime signature check on every read, same
model as Agent Skills / Agent Spaces) and swaps snapshots atomically.

## On-disk shape

```json
{
  "id": "filesystem-dev",
  "name": "Local filesystem (dev)",
  "url": "http://localhost:8811/mcp",
  "authHeaderName": "Authorization",
  "authHeaderValue": "v2:...(AES-256-GCM ciphertext — write plaintext via the admin API; the registry encrypts on save)...",
  "enabledTools": ["read_file", "list_directory"]
}
```

| Field             | Required | Type       | Notes                                                                 |
|-------------------|----------|------------|------------------------------------------------------------------------|
| `id`              | yes      | string     | kebab-case, `[a-z0-9][a-z0-9-]{0,63}`. Filename stem.                 |
| `name`            | yes      | string     | Display name for the admin panel.                                     |
| `url`             | yes      | string     | `http(s)://...` streamable-http MCP endpoint.                         |
| `authHeaderName`  | no       | string     | Header name sent with every outbound call. Set together with the value, or both omitted. |
| `authHeaderValue` | no       | string     | Encrypted at rest (`v2:` AES-256-GCM prefix, written by the registry).|
| `enabledTools`    | no       | `string[]` | Explicit allowlist. **Empty (the default) = nothing enabled.**        |

Unknown top-level keys are **rejected** — a typo surfaces as
`UNKNOWN_FIELD` rather than being silently dropped, same discipline as
Agent Spaces.

## How a call actually happens

1. **Discovery.** `McpOutboundToolCatalog` asks every registered server
   with a non-empty `enabledTools` for its `tools/list` (via
   `McpOutboundClient`, a JDK-`HttpClient`-only JSON-RPC 2.0 client
   speaking the same `initialize` → `tools/list` / `tools/call`
   streamable-http protocol the inbound `McpResource` already serves).
   Only tools BOTH discovered AND admin-enabled make it into the
   flattened catalogue, named `mcp__<server-id>__<tool-name>`.
   Cache-refreshed periodically (60s TTL) and immediately on any
   registry write.
2. **Failure isolation.** An unreachable server, a timeout, or a
   malformed `tools/list` response simply drops that server's tools from
   the catalogue THIS refresh — never a hard error that would take down
   the ask flow. Per the saiku#1425 acceptance criteria: "outbound
   server unreachable → the LLM sees the tool absent, not a hard error."
3. **Ask-layer integration.** `AiAskService#askChained` (the bounded
   server-side agentic loop already used for "build a query, execute it,
   report on it") offers the enabled outbound tools alongside the
   built-in `emit_query` / `emit_insight` / … functions on every
   AUTO-routed turn AND on the forced-report continuation turn after a
   cube query executes — so a single ask can combine a cube query with
   external context in one answer ("what were last week's sales, and is
   there an open Sentry incident for the checkout service?").
4. **Dispatch.** When the model calls an `mcp__*` tool, the server
   dispatches it through `McpOutboundToolCatalog#callTool` — which
   RE-VALIDATES the tool is still enabled (defence in depth against a
   stale or fabricated tool name in the model's response) before
   forwarding to `McpOutboundClient#callTool`. The result (or failure
   reason) is fed back to the model as the next turn's context, exactly
   like a query's result digest — capped at 20,000 characters so a
   misbehaving remote tool can't blow the prompt budget.

## Security

- **Credentials are server-side only.** The configured header name/value
  come exclusively from the `McpOutboundServer` record; nothing derived
  from a tool-call argument, the user's question, or the remote server's
  own response ever reaches the outbound HTTP header. A malicious or
  compromised remote server cannot make Saiku attach a different
  credential to a *future* call — the header is set once, per request,
  from config alone.
- **Per-user identity** flows the same way every other outbound call in
  Saiku does today — there is no separate outbound-MCP identity model in
  this cut. Operators who need per-user attribution on the remote side
  should mint a shared service credential scoped to what the connector
  should see, same as any other server-to-server integration.
- **Nothing is enabled by default.** Registering a server does not, by
  itself, expose anything to the LLM — see `enabledTools` above.

## Admin REST surface (`/rest/saiku/admin/mcp-servers`, `ROLE_ADMIN`)

- `GET  /mcp-servers` — registered servers, credentials omitted (only
  whether one is set).
- `GET  /mcp-servers/errors` — parse errors from malformed registration
  files.
- `GET  /mcp-servers/{id}/tools` — live `tools/list` against one
  registered server, each entry flagged `enabled: true/false` against
  the CURRENT `enabledTools` — this is how an admin picks what to
  enable. `502` (`UNREACHABLE`) if the server can't be reached.
- `PUT  /mcp-servers/{id}` — create or replace. `authHeaderValue: null`
  keeps the existing credential unchanged (same "blank means unchanged"
  convention as editing a saved password); an explicit empty string
  clears it.
- `DELETE /mcp-servers/{id}` — remove a registration.
- `POST /mcp-servers/refresh` — force a rescan of the registry and the
  ask-layer tool catalogue cache.

## Canonical template — filesystem MCP for local dev testing

The [Model Context Protocol reference servers](https://github.com/modelcontextprotocol/servers)
include a filesystem server that's the simplest way to exercise outbound
MCP end-to-end on a laptop: `npx -y @modelcontextprotocol/server-filesystem <dir>`
(or the equivalent Docker image) serves `read_file` / `list_directory` /
`search_files` etc. over stdio; front it with any streamable-http bridge
(or run an MCP-over-HTTP-capable build) on `http://localhost:8811/mcp`,
then register it:

```bash
curl -X PUT http://localhost:8080/rest/saiku/admin/mcp-servers/filesystem-dev \
  -u admin:admin -H 'content-type: application/json' \
  -d '{
    "name": "Local filesystem (dev)",
    "url": "http://localhost:8811/mcp",
    "enabledTools": []
  }'

# Discover what it offers, then enable specific tools:
curl -u admin:admin http://localhost:8080/rest/saiku/admin/mcp-servers/filesystem-dev/tools

curl -X PUT http://localhost:8080/rest/saiku/admin/mcp-servers/filesystem-dev \
  -u admin:admin -H 'content-type: application/json' \
  -d '{
    "name": "Local filesystem (dev)",
    "url": "http://localhost:8811/mcp",
    "enabledTools": ["read_file", "list_directory"]
  }'
```

A working ask against a cube can now also reach `mcp__filesystem-dev__read_file`
mid-conversation.

## Non-goals for v1

- **A vetted connector directory / OAuth flows.** v1 ships the generic
  custom-HTTPS-endpoint path only (a single static header credential).
  A curated directory of pre-configured connectors (Notion, Linear,
  Sentry, …) with OAuth can layer on top of the same registry later
  without a schema change — `enabledTools` and the discovery flow are
  already directory-agnostic.
- **In-chat authorization prompts.** Some MCP-connector products ask the
  user to authorize a specific call inline before it runs. v1's
  authorization boundary is entirely admin-side (per-tool enablement);
  a user-facing consent step for an already-enabled tool is deferred.
- **Fuzz testing via the Hegel property-test harness** (`saiku-proptest`).
  `McpOutboundClientTest` covers the same failure classes (malformed
  JSON-RPC response, timeout, non-2xx, an oversized result) with
  hand-written cases; wiring genuinely randomised inputs through Hegel
  is left as follow-up.
- **Agent Space allowlist integration.** Agent Spaces currently gate
  cubes and skills; extending `cubeAllowlist`/`skillAllowlist`-style
  scoping to outbound MCP tools per-persona is a natural follow-up but
  is not wired in this cut — every enabled outbound tool is visible to
  every space today.

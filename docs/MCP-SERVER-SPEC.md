# Saiku MCP Server — Tool Surface Spec

> **This is the design spec. For the shipped implementation see
> [`saiku-mcp/`](../saiku-mcp/) and [`saiku-mcp/README.md`](../saiku-mcp/README.md)
> (build, run, Claude Desktop config). For the underlying REST contract
> this spec maps to, see [`AI-QUERY-API.md`](AI-QUERY-API.md).**
>
> Order of precedence when the three diverge: **REST contract > shipped
> implementation > spec**. The spec exists to record original intent —
> the running code is what agents actually see, and the REST contract
> is the gravity well.

A standalone Model Context Protocol (MCP) server that wraps Saiku's
`/saiku/api/ai/*` REST API as a tool surface for LLM agents. Claude Desktop,
Cursor, Cline, and Continue users gain one-line access to Saiku cubes without
ever seeing the REST contract, MDX, or `cubeId` strings.

This document specifies the **tool surface** — names, descriptions,
input/output schemas, and few-shot examples — designed for LLM consumption.
The implementation is intentionally thin: each tool is a typed wrapper around
one REST call. Originally drafted as a Node/Python project (~300 LOC); the
shipped version is Java/JDK-21 (~500 LOC including tests) so it lives in
the saiku monorepo, builds via the same `mvn` invocation as the rest of
the project, and avoids a Node toolchain dependency for ops.

---

## Why an MCP server (and why now)

The REST API is excellent for code-driven integration but invisible to the
agent-tool ecosystem. Claude Desktop's "add MCP server" UX is a single
config-line; the REST API needs SDK glue and prompt-engineering before an
agent will even consider using it.

The MCP wrapper closes that gap. With this spec implemented:

- Agents discover Saiku cubes the same way they discover filesystem or git
  resources — via `tools/list`.
- Tool descriptions are written as LLM-facing prose ("Run a Semantic Layer
  analytical query…"), not endpoint docs.
- Each tool ships a few-shot example tuned to the kind of natural-language
  question a user actually asks ("top 5 product families by sales last
  quarter") rather than the REST body.

---

## Architecture

```
┌───────────────┐    MCP JSON-RPC    ┌──────────────────┐   HTTPS    ┌──────────────┐
│ Claude Desktop│ ◄────── stdio ────►│  saiku-mcp       │ ────►───► │  Saiku       │
│ Cursor        │                    │  (Node/Python)    │            │  /saiku/api/ │
│ Cline         │                    │                   │ ◄────◄──── │  ai/*        │
└───────────────┘                    │  Stateless        │  JSON      │  (Jetty 12)  │
                                     │  per-call auth    │            └──────────────┘
                                     └──────────────────┘
```

**Stateless wrapper.** The MCP server holds no schema cache, no query
session, no result memory. Every tool call is one inbound MCP request → one
outbound HTTPS request. The REST API already caches schemas and async query
results; duplicating that in the MCP layer is wasted code.

**Authentication.** Two modes, picked per deployment:

1. **API key** — operator generates a service-account key in Saiku admin,
   passes it via `SAIKU_API_KEY` env var on the MCP server. The server adds
   `Authorization: Bearer <key>` to every outbound call.
2. **Cookie passthrough** — for browser-launched MCP scenarios (rare today;
   future Claude-in-browser). The MCP client supplies a session cookie via
   tool-call metadata.

The MCP server itself stores no credentials beyond the env var.

**Transports.** Two:

- `stdio` (default) — for Claude Desktop / Cursor / Cline. One subprocess
  per MCP client. Configured via the client's MCP-server JSON entry.
- `sse` (optional) — for hosted multi-user MCP gateways. Same tool surface,
  but the server runs as a long-lived HTTP endpoint.

---

## Tool surface

Six primary tools + four async helpers. Names are imperative verbs (the LLM
reads them as actions). Descriptions are written as one-paragraph "when to
use this" prose, optimised for tool-routing decisions.

### Primary tools (95% of agent traffic)

#### `list_cubes`

> List every Semantic Layer cube the current user can query. Use this first when you
> don't know what data is available — the response includes a one-line
> caption per cube and the default measure, which is usually enough to pick
> the right one without describing each cube. Returns at most a few dozen
> entries; not paginated.

**Input:** `{}` (no params)

**Output:**

```json
{
  "cubes": [
    {
      "id": "unknown_foodmart/FoodMart/FoodMart/Sales",
      "caption": "Sales",
      "defaultMeasure": "Unit Sales",
      "measureCount": 8
    }
  ]
}
```

Maps to: `GET /saiku/api/ai/cubes`. The `id` is the value to pass to other
tools' `cube` arg — agents never construct it by hand.

---

#### `describe_cube`

> Get the complete queryable structure of one cube: measures, dimensions,
> hierarchies, levels, and sample members with their MDX unique names.
> Always call this before run_query if you haven't seen the cube
> structure yet — it tells you exactly which names are valid and includes
> ready-made example query bodies. Sample members include both the
> human caption and the MDX unique name, so you can copy the unique name
> directly into a filter without constructing it.

**Input:**

```json
{ "cube": "unknown_foodmart/FoodMart/FoodMart/Sales" }
```

**Output:** The full `AiSchema` object — see `docs/AI-QUERY-API.md` "Step 2".
Highlights:

- `measures` — map of canonical name → `{name, uniqueName, description, displayName?}`
- `dimensions[].hierarchies[].levels[]` — same nested shape
- `dimensions[].hierarchies[].levels[].sampleMembers` — `[{caption, uniqueName}]`,
  ≤5 entries per level, deduped
- `examples` — 2–3 ready-made `AiQueryRequest` bodies
- `requestSchema` — the JSON Schema (draft 2020-12) the agent can self-validate against

Maps to: `GET /saiku/api/ai/schema/{cubeId}`.

---

#### `search_members`

> Find the MDX unique names of members on a level by substring match. Use
> when the cube has more members at a level than sample_members covered
> (e.g. searching for a specific city, customer, or product brand), or
> when the user says "filter by Italy" and you need to confirm Italy is
> spelled that way in the cube. Returns up to limit hits.

**Input:**

```json
{
  "cube": "unknown_foodmart/FoodMart/FoodMart/Sales",
  "dimension": "Store",
  "hierarchy": "Stores",
  "level": "Store Country",
  "q": "USA",
  "limit": 20
}
```

`hierarchy` is optional when the dimension has only one. `q` is optional;
omit it to list all members on the level up to `limit`.

**Output:**

```json
{
  "hits": [
    { "caption": "USA", "uniqueName": "[Store].[Stores].[Store Country].&[USA]" }
  ]
}
```

Maps to: `GET /saiku/api/ai/members/search`.

---

#### `run_query`

> Run a Semantic Layer analytical query and get the results as records. This is
> the primary tool — most user questions land here. Build the request
> against the cube structure from describe_cube; the server validates
> every name and returns a 400 with a list of valid alternatives if any
> name is wrong, so don't pre-validate yourself. Default output is
> records (one object per row, keyed by column captions); pass
> format:"matrix" only if you need the position-indexed shape.

**Input:** the `AiQueryRequest` JSON (see `docs/AI-QUERY-API.md` "Request body").
The MCP tool's input schema mirrors `requestSchema` from `describe_cube`.

Minimum:

```json
{
  "cube": "unknown_foodmart/FoodMart/FoodMart/Sales",
  "measures": [{ "name": "Store Sales" }],
  "rows": [{ "dimension": "Product", "hierarchy": "Products", "level": "Product Family" }],
  "order": [{ "by": "Store Sales", "direction": "desc" }],
  "limit": 5
}
```

Optional advanced fields: `columns`, `filters`, `visualTotals`, `nonEmpty`.
Filters support five ops: `in` (default), `not_in`, `between`,
`descendants_of`, and `relative` (for natural-language time filters — see
example 4 below).

**Output:**

```json
{
  "queryId": "uuid",
  "status": "SUCCESS",
  "format": "records",
  "metadata": {
    "rows": [ { "name": "...", "caption": "..." } ],
    "columns": [ { "name": "...", "caption": "..." } ],
    "generatedMdx": "SELECT NON EMPTY ...",
    "freshness": { "computedAt": "2026-05-15T10:23:00Z", "cached": false }
  },
  "data": [
    {
      "Product Family": "Food",
      "Store Sales":    { "value": 409035.59, "formatted": "409,035.59", "unit": null }
    }
  ],
  "totalRows": 3,
  "runtimeMs": 421
}
```

On validation failure:

```json
{
  "status": "VALIDATION_ERROR",
  "error": "Unknown measure 'Made Up Measure'",
  "field": "measures[].name",
  "available": ["Unit Sales", "Store Sales", "Store Cost", "..."]
}
```

The MCP tool surfaces validation errors as a normal tool result (not an MCP
error), because the agent should self-correct from `field` + `available`
rather than fail.

Maps to: `POST /saiku/api/ai/query`.

---

#### `preview_query`

> Compile a query to MDX without executing it. Use when you want to show
> the user what the query will do, audit a generated query, or estimate
> cost before running an expensive aggregation. Validation runs the same
> as run_query — preview will return the same VALIDATION_ERROR shape if
> names don't resolve.

**Input:** same as `run_query`.

**Output:**

```json
{
  "queryId": "uuid",
  "status": "PREVIEW",
  "generatedMdx": "SELECT NON EMPTY {[Measures].[Store Sales]} ON COLUMNS,\nNON EMPTY TopCount(...) ON ROWS\nFROM [Sales]"
}
```

Maps to: `POST /saiku/api/ai/query/preview`.

---

#### `drillthrough`

> Fetch the raw fact-table rows behind a specific query. Use when the user
> asks "show me the underlying transactions" or wants to inspect detail
> for a single cell. Pass the queryId returned by an earlier run_query
> call. Cells in the response are the same typed envelope as run_query —
> numeric warehouse columns get a parsed value.

**Input:**

```json
{
  "queryId": "49127ee9-0ee2-4337-8560-41df11c3d458",
  "maxrows": 100,
  "returns": null
}
```

`returns` is an optional comma-separated column list to project a subset.

**Output:**

```json
{
  "queryId": "...",
  "rowCount": 5,
  "rows": [
    {
      "Year":        { "value": 1997.0, "formatted": "1997", "unit": null },
      "Product":     { "value": null,   "formatted": "Excellent Coffee", "unit": null },
      "Store Sales": { "value": 104.3,  "formatted": "104.3000", "unit": null }
    }
  ]
}
```

Maps to: `GET /saiku/api/ai/query/{queryId}/drillthrough`.

---

### Async helpers (rarely needed)

For queries that take seconds to minutes, the async pattern unblocks the
agent's tool loop. The MCP tool surface mirrors the REST four-step dance:

- `run_query_async` → returns immediately with a `queryId`
- `query_status` → poll for `PENDING` / `RUNNING` / `DONE` / `FAILED` / `CANCELLED`
- `query_result` → fetch the materialised result once `DONE` (same shape as `run_query`)
- `cancel_query` → best-effort cancel against the live Mondrian statement

Most agents won't reach for these unless a `run_query` call times out at the
MCP-transport layer. Document them, but the LLM-routing primary path is
sync `run_query`.

---

## Few-shot examples

These belong in the tool descriptions themselves (some MCP clients show
them; all of them feed the tool-routing LLM). Each example is a
user-prompt → tool-call pair.

### Example 1 — discovery

> **User:** "What data do we have?"
>
> **Agent call:**
> ```json
> { "tool": "list_cubes" }
> ```

### Example 2 — typed query, top-N

> **User:** "Top 5 product families by store sales."
>
> **Agent calls describe_cube first** (if it hasn't already seen the schema),
> then:
> ```json
> { "tool": "run_query",
>   "args": {
>     "cube": "unknown_foodmart/FoodMart/FoodMart/Sales",
>     "measures": [{ "name": "Store Sales" }],
>     "rows": [{ "dimension": "Product", "hierarchy": "Products", "level": "Product Family" }],
>     "order": [{ "by": "Store Sales", "direction": "desc" }],
>     "limit": 5
>   }
> }
> ```

### Example 3 — filter by member

> **User:** "Same thing but only for USA stores."
>
> **Agent:** sees no "USA" in the cached schema's sample members for
> Store Country, so calls `search_members` to confirm the unique name,
> then:
> ```json
> { "tool": "run_query",
>   "args": {
>     "cube": "unknown_foodmart/FoodMart/FoodMart/Sales",
>     "measures": [{ "name": "Store Sales" }],
>     "rows": [{ "dimension": "Product", "hierarchy": "Products", "level": "Product Family" }],
>     "filters": [{
>       "dimension": "Store", "hierarchy": "Stores", "level": "Store Country",
>       "op": "in",
>       "members": ["[Store].[Stores].[Store Country].&[USA]"]
>     }],
>     "order": [{ "by": "Store Sales", "direction": "desc" }],
>     "limit": 5
>   }
> }
> ```

### Example 4 — natural-language time

> **User:** "Last 30 days of sales by product family."
>
> **Agent:** uses the `relative` filter op so it doesn't have to know
> what today's date is:
> ```json
> { "tool": "run_query",
>   "args": {
>     "cube": "unknown_foodmart/FoodMart/FoodMart/Sales",
>     "measures": [{ "name": "Store Sales" }],
>     "rows": [{ "dimension": "Product", "hierarchy": "Products", "level": "Product Family" }],
>     "filters": [{
>       "dimension": "Time", "hierarchy": "Time By", "level": "Day",
>       "op": "relative", "value": "last_n_days", "n": 30
>     }]
>   }
> }
> ```

### Example 5 — preview before running

> **User:** "Compute total sales by country and quarter — don't run it
> yet, show me the query."
>
> **Agent call:**
> ```json
> { "tool": "preview_query",
>   "args": { /* same shape as run_query */ }
> }
> ```

### Example 6 — drill into a cell

> **User:** "Show me the actual transactions behind the Drink/Q4 cell."
>
> **Agent:** keeps `queryId` from the previous `run_query` response and:
> ```json
> { "tool": "drillthrough",
>   "args": {
>     "queryId": "49127ee9-0ee2-4337-8560-41df11c3d458",
>     "maxrows": 50
>   }
> }
> ```

### Example 7 — self-correction on validation error

> **Agent:** sends a query with `"measures": [{ "name": "Revenue" }]`.
> Server returns:
> ```json
> { "status": "VALIDATION_ERROR",
>   "error": "Unknown measure 'Revenue'",
>   "field": "measures[].name",
>   "available": ["Store Sales", "Unit Sales", "Profit", "..."] }
> ```
> **Agent:** notices "Store Sales" is the closest match in `available`,
> retries with `"name": "Store Sales"`. No user round-trip needed.

---

## Error mapping

| Source | MCP surface |
| --- | --- |
| HTTP 400 with `status:VALIDATION_ERROR` body | Tool result (not MCP error). Agent self-corrects from `field`+`available`. |
| HTTP 401 / 403 | MCP error — surfaces as `Authentication required` so the operator regenerates the API key. |
| HTTP 404 (unknown queryId in drillthrough/async) | Tool result with `status:CUBE_NOT_FOUND` or equivalent. |
| HTTP 5xx | MCP error with the underlying message. Don't retry from the MCP layer. |
| Network timeout | MCP error with one-line "Saiku server unreachable at <url>". |

The discipline: validation errors are *part of the contract* (they teach the
agent the valid name set), so they're tool results. Infrastructure failures
are *operator concerns*, so they're MCP errors.

---

## Implementation sketch (Node.js + TypeScript)

```typescript
// src/server.ts (~150 LOC)
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';

const SAIKU_BASE = process.env.SAIKU_BASE_URL!;       // e.g. https://saiku.internal/saiku
const SAIKU_KEY  = process.env.SAIKU_API_KEY!;

async function saikuFetch(path: string, init?: RequestInit) {
  const res = await fetch(`${SAIKU_BASE}/api/ai${path}`, {
    ...init,
    headers: {
      'Authorization': `Bearer ${SAIKU_KEY}`,
      'Content-Type':  'application/json',
      ...(init?.headers ?? {})
    }
  });
  const body = await res.json();
  if (res.status >= 500) throw new Error(`Saiku ${res.status}: ${body.error ?? res.statusText}`);
  if (res.status === 401 || res.status === 403) throw new Error('Authentication required');
  return body; // 200s and 400s alike are surfaced as tool results
}

const server = new Server({ name: 'saiku-mcp', version: '0.1.0' }, {
  capabilities: { tools: {} }
});

server.setRequestHandler('tools/list', async () => ({
  tools: [
    { name: 'list_cubes',     description: '...', inputSchema: { type: 'object', properties: {} } },
    { name: 'describe_cube',  description: '...', inputSchema: { /* cube: string */ } },
    { name: 'search_members', description: '...', inputSchema: { /* … */ } },
    { name: 'run_query',      description: '...', inputSchema: { /* full AiQueryRequest */ } },
    { name: 'preview_query',  description: '...', inputSchema: { /* same */ } },
    { name: 'drillthrough',   description: '...', inputSchema: { /* queryId, maxrows, returns */ } },
    // async helpers ...
  ]
}));

server.setRequestHandler('tools/call', async ({ params }) => {
  switch (params.name) {
    case 'list_cubes':
      return { content: [{ type: 'text', text: JSON.stringify({ cubes: await saikuFetch('/cubes') }) }] };
    case 'describe_cube':
      return { content: [{ type: 'text', text: JSON.stringify(await saikuFetch(`/schema/${params.arguments.cube}`)) }] };
    case 'run_query':
      return { content: [{ type: 'text', text: JSON.stringify(await saikuFetch('/query', {
        method: 'POST', body: JSON.stringify(params.arguments)
      })) }] };
    // ... rest follows the same pattern
    default:
      throw new Error(`Unknown tool: ${params.name}`);
  }
});

await server.connect(new StdioServerTransport());
```

Real implementation will also:

- Read tool descriptions from a `tools.json` shipped alongside (the
  descriptions in this doc), keeping the prose out of the code.
- Validate inputs against the same JSON Schema the cube's `requestSchema`
  exposes, so the MCP layer catches obvious shape errors before they reach
  Saiku.
- Support `?format=matrix` on `run_query` via an optional MCP arg.
- Expose `transport: 'sse'` mode with a tiny Express wrapper for hosted use.

Total ~250–300 LOC including tests against a stubbed REST fixture.

---

## Configuration

Operator-facing env vars:

| Variable | Required | Notes |
| --- | --- | --- |
| `SAIKU_BASE_URL` | yes | Root of the Saiku web app, e.g. `https://saiku.internal/saiku`. The MCP server prefixes `/api/ai/*`. |
| `SAIKU_API_KEY` | yes (one of) | Bearer token for a service-account user with read access to the cubes you want to expose. |
| `SAIKU_SESSION_COOKIE` | yes (one of) | Alternative: a long-lived session cookie. Either this or the API key. |
| `SAIKU_MCP_TRANSPORT` | no | `stdio` (default) or `sse`. |
| `SAIKU_MCP_PORT` | no | Required when `transport=sse`; ignored otherwise. |
| `SAIKU_MCP_TIMEOUT_MS` | no | Per-call HTTP timeout. Default 60000. Sync `run_query` calls that exceed this should switch to `run_query_async`. |

Claude Desktop / Cursor `mcpServers` block (stdio mode):

```json
{
  "saiku": {
    "command": "node",
    "args": ["/opt/saiku-mcp/dist/server.js"],
    "env": {
      "SAIKU_BASE_URL": "https://saiku.internal/saiku",
      "SAIKU_API_KEY":  "secret-bearer-token"
    }
  }
}
```

---

## What's intentionally out of scope

- **No client-side query construction.** The MCP server doesn't build
  MDX, doesn't cache schemas, doesn't paginate. It's a tool surface.
- **No transformation layer.** Records come back exactly as the REST
  endpoint returns them; the LLM does the rendering. Resist the urge to
  flatten cells to bare numbers in the MCP server — the agent loses
  format + unit info.
- **No saved-query catalog tool.** The Saiku repository's saved-`.saiku`
  files are a UI concept; the MCP audience is agents building queries
  fresh from the schema each time.
- **No "explain" tool.** `preview_query` already returns the MDX. A
  separate explain-plan endpoint can land later if there's demand.

---

## Roll-out

1. **Implementation** — `~/saiku-mcp` as a separate repo (Node.js +
   `@modelcontextprotocol/sdk`). Tool descriptions live in
   `tools.json`. Tests stub Saiku with `msw`/`nock` fixtures derived
   from the AI-QUERY-API doc's response examples.
2. **Distribution** — `npx saiku-mcp` for Claude Desktop / Cursor
   integration; Docker image for SSE-mode hosted deployments.
3. **Discoverability** — list under Saiku docs + submit to the
   `awesome-mcp-servers` index. The Claude Desktop "add MCP server"
   flow auto-suggests via npm package metadata.
4. **Versioning** — track the REST API's contract version (currently
   v3). Bump the MCP package major version when the REST contract
   makes a breaking change. Minor versions for tool description
   improvements.

---

## Pre-launch checklist

- [ ] Tool descriptions reviewed by a non-engineer for prompt-routing clarity
- [ ] Each tool description includes at least one few-shot example
- [ ] Input schemas validated against the AI-QUERY-API JSON Schema
- [ ] Error mapping (HTTP → MCP) covers the full `Status` enum
- [ ] Stdio transport tested against Claude Desktop on macOS, Windows, Linux
- [ ] SSE transport behind a reverse proxy with HTTPS + auth gateway
- [ ] README in the MCP repo links back to `docs/AI-QUERY-API.md` for protocol details

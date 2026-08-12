# Saiku MCP Server — the front door

Saiku ships a **native MCP (Model Context Protocol) server**: any MCP-capable
agent — Claude Desktop, Claude Code, Cursor, or your own — can list cubes,
introspect their shape, run typed queries, and drill to fact rows **without
ever writing MDX or SQL**. The server is not a sidecar: it lives inside the
Saiku webapp at

```
POST /rest/saiku/api/mcp
```

speaking streamable-HTTP JSON-RPC behind the **same Spring Security chain as
the REST API** — one auth model, per-user identity, no separate process to
deploy (issue #878; the old standalone `saiku-mcp` JAR + proxy are gone, see
[mcp-native-migration.md](../mcp-native-migration.md)).

Related deep docs: [AI-QUERY-API.md](../AI-QUERY-API.md) (the typed REST
surface every MDX tool delegates to), [AI-OSSIE-API.md](../AI-OSSIE-API.md)
(the SQL-side equivalent), [MCP-SERVER-SPEC.md](../MCP-SERVER-SPEC.md) (the
original design spec).

## Quickstart — Claude Desktop in about a minute

1. Run Saiku (any install — the [dist zip](../../dist/README.md), Docker, or
   a dev launcher):

   ```bash
   docker run -d -p 8080:8080 --name saiku ghcr.io/spiculedata/saiku:latest
   ```

2. Download the one-click bundle your server generates for itself:

   ```
   http://localhost:8080/rest/saiku/info/mcp.dxt
   ```

3. Open the `.dxt` with Claude Desktop (double-click / drag in). At install
   time it prompts for your **Saiku username + password** (`user_config` —
   credentials are never baked into the bundle) and wires a stdio↔HTTP
   bridge that attaches them as Basic auth on every call.

4. Ask Claude: *"What cubes exist in Saiku?"* → it calls `list_cubes`, then
   `describe_cube`, then `run_query` — typed results, no MDX in sight.

### Any other MCP client (Claude Code, Cursor, custom agents)

Point a streamable-HTTP MCP client at the endpoint with Basic auth. Claude
Code, for example:

```bash
claude mcp add saiku --transport http http://localhost:8080/rest/saiku/api/mcp \
  --header "Authorization: Basic $(printf 'admin:admin' | base64)"
```

`GET /rest/saiku/info/capabilities` reports the MCP URL a server advertises —
it auto-derives from the request's scheme + host, so localhost and production
both work unconfigured; set `SAIKU_MCP_URL` only when TLS terminates at a
proxy with a different public hostname.

There is **no anonymous handshake**: even `initialize` requires credentials —
unauthenticated calls get a 401 before reaching the MCP layer.

## Tool reference

Twelve tools, in two families. Every one delegates to the corresponding
typed REST endpoint, so the request/response contracts (and the
self-correcting `VALIDATION_ERROR` envelopes with `field` + `available`
candidate lists) are identical to the documented REST surface.

### MDX / cube family (contract: [AI-QUERY-API.md](../AI-QUERY-API.md))

| Tool | What it does |
|------|--------------|
| `list_cubes` | Every queryable cube with its `connection/catalog/schema/cube` ref |
| `describe_cube` | Self-describing schema: measures, dimensions, sample members with unique names, example request bodies |
| `search_members` | Find dimension members by fragment — how an agent resolves "USA" to `[Store].[Stores].[USA]` |
| `run_query` | Execute a typed query (records or matrix format; `{value, formatted, unit}` cells) |
| `preview_query` | Same body as `run_query`, returns the generated MDX without executing (cost check / audit / "show what's about to run") |
| `drillthrough` | Raw fact rows behind a result, with `returns=` projection (bare captions accepted) |

### Ossie / SQL-semantic family (contract: [AI-OSSIE-API.md](../AI-OSSIE-API.md))

| Tool | What it does |
|------|--------------|
| `list_ossie_models` | Every Ossie semantic model (datasets, metrics) |
| `describe_ossie_model` | Model shape: datasets, fields, metrics, synonyms |
| `describe_ossie_ontology` | The model's ontology block (entities + relationships) |
| `run_ossie_query` | Execute a typed shelf query against a model |
| `preview_ossie_query` | Generated SQL + shape without executing |
| `search_field_values` | Find field values by fragment (the Ossie sibling of `search_members`) |

## Permissions and data isolation — what an agent can and cannot see

The MCP surface adds **no new privileges**. Every call runs as the
authenticated Saiku user, through the exact same enforcement stack as the UI
and REST API:

- **Per-user identity, audited.** Each MCP call lands in the structured
  audit log under the caller's real username (there is no shared service
  account). Bad-credential storms on the endpoint get the same
  5-per-15-minutes 429 rate-limit as the login form.
- **Mondrian role propagation.** Connections are re-acquired per call and
  the caller's Mondrian role is re-applied on every hand-out — a user whose
  role restricts FoodMart to USA sees only USA rows from `run_query`, no
  matter which agent issued the call.
- **k-anonymity small-cell suppression.** Aggregated results cross the AI
  boundary through the same suppression filter as `/ai/query` (rows whose
  backing count is below `ai.kAnonymity`, default 5, are masked).
- **AI data policy.** The `ai.policy` setting (e.g. `AGGREGATED` vs full)
  gates what data classes may cross to an agent at all; `drillthrough`
  (raw rows) is refused under an aggregated-only policy.
- **No raw MDX/SQL entry point.** Tools accept only the typed request
  shapes; the server generates MDX/SQL internally. An agent cannot smuggle
  arbitrary MDX/SQL through this surface.
- **Agent Spaces.** Persona-scoped asks (cube allowlists, skill filters)
  apply server-side where a space is used — an agent can be narrowed, never
  widened, by client-supplied input.

## A worked conversation (what the tool flow looks like)

> **User:** How did sales do by product family last year?
>
> **Agent → `list_cubes`** — finds `foodmart/FoodMart/FoodMart/Sales`.
>
> **Agent → `describe_cube`** — learns measures (`Unit Sales`, `Store
> Sales`…), the `Product` dimension's `Product Family` level, the `Time`
> hierarchy, and copies a ready-made example body.
>
> **Agent → `run_query`** — `measures: [{name: "Unit Sales"}]`, `rows:
> [{dimension: "Product", hierarchy: "Products", level: "Product Family"}]`,
> `filters: [{dimension: "Time", level: "Year", members: ["[Time].[1997]"]}]`.
>
> **Server →** typed records — `Drink / 24,597`, `Food / 191,940`,
> `Non-Consumable / 50,236`.
>
> **User:** Which drinks drove that?
>
> **Agent → `drillthrough`** with `returns=Product Family,Store Sales` — raw
> fact rows behind the Drink cell.
>
> If the agent names something wrong (`"Store Salez"`), the server answers
> with `VALIDATION_ERROR`, `field=measures[].name`, and an `available` list —
> the agent corrects itself on the next call without any prompt engineering.

## Troubleshooting

- **401 on `initialize`** — credentials missing/wrong; the endpoint requires
  auth for the handshake itself. Re-install the DXT (it re-prompts for
  credentials on upgrade) or fix the `Authorization` header.
- **429** — credential storm tripped the rate limiter; wait out the window.
- **Tools list but `run_query` refuses raw rows** — that's the `ai.policy`
  gate working as intended; use aggregated queries or relax the policy
  server-side.
- **Wrong MCP URL behind a proxy** — set `SAIKU_MCP_URL` to the public URL;
  check `GET /rest/saiku/info/capabilities` reflects it.

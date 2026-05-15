# saiku-mcp

Standalone MCP (Model Context Protocol) server that wraps Saiku's
`/saiku/api/ai/*` REST surface as a tool surface for LLM agents.

Per [`docs/MCP-SERVER-SPEC.md`](../docs/MCP-SERVER-SPEC.md).

## What you get

Six tools — agents call these, the server forwards to Saiku's REST API:

| Tool             | What it does                                                |
| ---------------- | ----------------------------------------------------------- |
| `list_cubes`     | List queryable cubes.                                       |
| `describe_cube`  | Get the full typed schema for a cube.                       |
| `search_members` | Substring-match members on a level.                         |
| `run_query`      | Execute an analytical query, return records or matrix.      |
| `preview_query`  | Compile to MDX without executing — useful for audit / cost. |
| `drillthrough`   | Get raw fact rows behind a prior query.                     |

All tool descriptions are written for LLM consumption (when to use, what to
expect), not endpoint docs.

## Build

```sh
mvn -pl saiku-mcp -am -DskipTests package
```

Output: `saiku-mcp/target/saiku-mcp-4.0.1.jar` (fat jar, all deps shaded).

## Run

```sh
SAIKU_URL=http://localhost:8080 \
SAIKU_USER=admin \
SAIKU_PASS=admin \
java -jar saiku-mcp/target/saiku-mcp-4.0.1.jar
```

Stdin / stdout: MCP JSON-RPC framing. Stderr: SLF4J logging. Don't mix them.

Defaults: `SAIKU_URL=http://localhost:8080`, `SAIKU_USER=admin`,
`SAIKU_PASS=admin`. Override via env.

## Claude Desktop config

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`
(macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "saiku": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/saiku-mcp-4.0.1.jar"],
      "env": {
        "SAIKU_URL": "http://localhost:8080",
        "SAIKU_USER": "admin",
        "SAIKU_PASS": "admin"
      }
    }
  }
}
```

Restart Claude Desktop. The tools appear under the slash-commands menu
prefixed with `saiku`.

## Cursor / Cline / Continue

Same shape — every MCP-compatible client takes `command` + `args` + `env`.
Drop the JSON snippet above into their respective config files.

## Architecture

```
┌───────────────┐    MCP JSON-RPC    ┌──────────────────┐   HTTPS    ┌──────────────┐
│ Claude Desktop│ ◄────── stdio ────►│  saiku-mcp       │ ────►───► │  Saiku       │
│ Cursor / Cline│                    │  (this fat jar)   │            │  /saiku/api/ │
└───────────────┘                    │  Stateless        │ ◄────◄──── │  ai/*        │
                                     │  per-call auth    │  JSON      │  (Jetty 12)  │
                                     └──────────────────┘            └──────────────┘
```

Stateless wrapper — no schema cache, no result memory. Every tool call is
one inbound MCP request → one Saiku REST call → one response.

Session auth: the client logs in once on first call (form-POST to
`/login`), holds the JSESSIONID cookie in memory, and re-logs-in
transparently on a 401.

## Smoke test

The same launcher you'd use for the regular REST surface. Boot it (per
`saiku-launcher/dist/run.sh`), then in another terminal:

```sh
(printf '%s\n%s\n%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0.1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_cubes","arguments":{}}}' ; sleep 3) \
  | java -jar saiku-mcp/target/saiku-mcp-4.0.1.jar
```

Should print `{"jsonrpc":"2.0","id":1,"result":...}` (initialize) followed
by `{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"[{\"cubeName\":\"Sales\",...}]"}]}}`.

## What this isn't

- **It's not a query builder** — the agent generates the JSON, this server
  forwards it. All validation lives in Saiku's REST layer.
- **It's not a schema cache** — every `describe_cube` hits the REST API.
  Saiku's own cache is the source of truth.
- **It's not multi-user** — one process, one Saiku session. Run a process
  per user / per agent.

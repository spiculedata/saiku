# Saiku MCP sample agents

Two minimal, dependency-light agents (Python and TypeScript) that connect to
Saiku's **native MCP server** and query cubes without writing a line of MDX.
They are the programmatic sibling of the Claude Desktop quickstart in
[`docs/mcp/README.md`](../../docs/mcp/README.md) — use them as the starting
point for your own agent, a cron job, or a CI smoke test.

Each agent runs in one of two modes:

| Mode | Trigger | What it does |
|------|---------|--------------|
| **Agent** | `ANTHROPIC_API_KEY` is set | Claude (Opus 4.8) drives the full tool loop: asks *"what cubes exist?"*, picks one, and answers *"give me sales by product family"* — deciding for itself which of Saiku's 12 MCP tools to call |
| **Smoke** | no API key | Deterministic, LLM-free connectivity check: `initialize` → `list_cubes` → `describe_cube` → runs one of the cube's own ready-made example queries through `run_query` |

Both modes talk to the same endpoint with the same auth, so a green smoke run
means the agent mode will connect too.

## Prerequisites

1. **A running Saiku** (any install — dist zip, Docker, dev launcher):

   ```bash
   docker run -d -p 8080:8080 --name saiku ghcr.io/spiculedata/saiku:latest
   ```

2. Optionally, an Anthropic API key for the agent mode:

   ```bash
   export ANTHROPIC_API_KEY=sk-ant-...
   ```

## Configuration (both variants)

| Env var | Default | Meaning |
|---------|---------|---------|
| `SAIKU_URL` | `http://localhost:8080` | Base URL of the Saiku server |
| `SAIKU_USER` | `admin` | Saiku username (Basic auth) |
| `SAIKU_PASSWORD` | `admin` | Saiku password |
| `ANTHROPIC_API_KEY` | *(unset)* | Enables the Claude-driven agent mode |
| `QUESTION` | *(built-in)* | Override the question the agent answers |

> The MCP endpoint is `{SAIKU_URL}/rest/saiku/api/mcp` — streamable-HTTP
> JSON-RPC behind the same Spring Security chain as the REST API. There is no
> anonymous handshake; every call (including `initialize`) carries Basic auth.

## Python

```bash
cd examples/mcp-agent/python
python -m venv .venv && . .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python agent.py
```

## TypeScript

```bash
cd examples/mcp-agent/typescript
npm install
npm start
```

## What the agent can and cannot see

The MCP surface adds **no new privileges** — every tool call runs as the
authenticated Saiku user, with Mondrian role propagation, k-anonymity
small-cell suppression, and the `ai.policy` data-egress gate applied
server-side. There is no raw MDX/SQL entry point. The full isolation model
is documented in [`docs/mcp/README.md`](../../docs/mcp/README.md#permissions-and-data-isolation--what-an-agent-can-and-cannot-see).

## Adapting to your own agent

Everything Saiku-specific in these samples is three things:

1. the endpoint URL (`/rest/saiku/api/mcp`),
2. the Basic-auth header, and
3. the opening question.

The rest is the generic MCP-client + Claude tool-use loop: list the server's
tools, hand their schemas to Claude, execute whatever Claude calls, feed the
results back, stop when Claude answers in prose. Swap the question and you
have a BI agent; wire the loop into your own framework and Saiku becomes one
tool provider among many.

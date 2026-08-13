"""Saiku MCP sample agent (Python).

Connects to Saiku's native MCP server (streamable HTTP + Basic auth) and
either lets Claude drive the tool loop (ANTHROPIC_API_KEY set) or runs a
deterministic LLM-free smoke check (no key). See ../README.md.
"""

import asyncio
import base64
import contextlib
import json
import os
import sys

from mcp import ClientSession

# mcp >= 2.0 renamed the transport helper and moved header configuration onto
# an httpx client; keep a fallback so the sample runs on 1.x installs too.
try:
    from mcp.client.streamable_http import create_mcp_http_client, streamable_http_client

    _MCP_V2 = True
except ImportError:  # mcp 1.x
    from mcp.client.streamable_http import streamablehttp_client

    _MCP_V2 = False

SAIKU_URL = os.environ.get("SAIKU_URL", "http://localhost:8080").rstrip("/")
SAIKU_USER = os.environ.get("SAIKU_USER", "admin")
SAIKU_PASSWORD = os.environ.get("SAIKU_PASSWORD", "admin")
MCP_URL = f"{SAIKU_URL}/rest/saiku/api/mcp"
QUESTION = os.environ.get(
    "QUESTION",
    "What cubes exist in Saiku? Pick the most interesting one and show me "
    "sales by product family (or the closest equivalent breakdown it offers).",
)

BASIC_AUTH = "Basic " + base64.b64encode(f"{SAIKU_USER}:{SAIKU_PASSWORD}".encode()).decode()


def tool_result_text(result) -> str:
    """Flatten an MCP CallToolResult into plain text for Claude / stdout."""
    parts = []
    for block in result.content:
        if getattr(block, "type", None) == "text":
            parts.append(block.text)
    return "\n".join(parts) if parts else "(empty result)"


async def smoke(session: ClientSession) -> None:
    """LLM-free connectivity check: list -> describe -> run one example query."""
    tools = await session.list_tools()
    names = [t.name for t in tools.tools]
    print(f"connected: {len(names)} tools -> {', '.join(sorted(names))}\n")

    cubes_raw = tool_result_text(await session.call_tool("list_cubes", {}))
    cubes = json.loads(cubes_raw)
    cube_list = cubes if isinstance(cubes, list) else cubes.get("cubes", [])
    if not cube_list:
        sys.exit("no cubes visible to this user — check datasources / roles")
    # Prefer a sales-ish cube for a demo that shows real rows; else take the first.
    first = next(
        (c for c in cube_list if "sales" in c.get("cubeName", "").lower()),
        cube_list[0],
    )
    cube_id = "/".join(
        first[k] for k in ("connectionName", "catalog", "schema", "cubeName")
    )
    print(f"picked cube: {cube_id}")

    schema_raw = tool_result_text(
        await session.call_tool("describe_cube", {"cube": cube_id})
    )
    schema = json.loads(schema_raw)
    print(
        f"described:  {len(schema.get('measures', []))} measures, "
        f"{len(schema.get('dimensions', []))} dimensions"
    )

    # Every describe_cube response ships ready-made example bodies — run one
    # verbatim, so the smoke test never has to guess at valid names.
    examples = schema.get("examples") or []
    if not examples:
        print("no example bodies on this cube — smoke check ends here (still OK)")
        return
    body = examples[0].get("request", examples[0])
    result_raw = tool_result_text(await session.call_tool("run_query", body))
    result = json.loads(result_raw)
    if result.get("status") not in (None, "SUCCESS"):
        sys.exit(f"run_query failed: {result.get('error')}")
    rows = result.get("data") or []
    print(f"ran example query: status={result.get('status')}, {len(rows)} data rows — smoke check PASSED")


async def agent(session: ClientSession) -> None:
    """Claude drives the loop: Saiku's MCP tools become Claude tools."""
    import anthropic

    client = anthropic.Anthropic()
    listed = await session.list_tools()
    tools = [
        {
            "name": t.name,
            "description": t.description or t.name,
            "input_schema": t.inputSchema,
        }
        for t in listed.tools
    ]
    print(f"agent mode: {len(tools)} Saiku tools handed to Claude")
    print(f"question:   {QUESTION}\n")

    messages = [{"role": "user", "content": QUESTION}]
    while True:
        response = client.messages.create(
            model="claude-opus-4-8",
            max_tokens=16000,
            thinking={"type": "adaptive"},
            system=(
                "You are a BI analyst working against a Saiku OLAP server via "
                "its MCP tools. Discover what exists before querying: call "
                "list_cubes first, then describe_cube for anything you intend "
                "to query — the description includes valid measure/dimension "
                "names and ready-made example bodies. If a call returns "
                "VALIDATION_ERROR, read its `available` list and correct "
                "yourself. Answer with the actual numbers, briefly."
            ),
            tools=tools,
            messages=messages,
        )
        messages.append({"role": "assistant", "content": response.content})

        if response.stop_reason != "tool_use":
            final = "".join(
                b.text for b in response.content if getattr(b, "type", None) == "text"
            )
            print("\n=== agent answer ===\n" + final)
            return

        results = []
        for block in response.content:
            if block.type != "tool_use":
                continue
            print(f"  -> {block.name}({json.dumps(block.input)[:120]}...)")
            try:
                out = await session.call_tool(block.name, block.input)
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": tool_result_text(out),
                        "is_error": bool(out.isError),
                    }
                )
            except Exception as e:  # surface transport errors to the model
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": f"tool call failed: {e}",
                        "is_error": True,
                    }
                )
        # All results for one assistant turn go back in ONE user message.
        messages.append({"role": "user", "content": results})


@contextlib.asynccontextmanager
async def connect():
    """Yield (read, write) streams for the Saiku MCP endpoint, on either SDK."""
    headers = {"Authorization": BASIC_AUTH}
    if _MCP_V2:
        async with create_mcp_http_client(headers=headers) as http:
            async with streamable_http_client(MCP_URL, http_client=http) as (read, write):
                yield read, write
    else:
        async with streamablehttp_client(MCP_URL, headers=headers) as (read, write, _):
            yield read, write


async def main() -> None:
    print(f"Saiku MCP endpoint: {MCP_URL} (user: {SAIKU_USER})")
    async with connect() as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            if os.environ.get("ANTHROPIC_API_KEY"):
                await agent(session)
            else:
                print("(no ANTHROPIC_API_KEY — running LLM-free smoke check)\n")
                await smoke(session)


if __name__ == "__main__":
    asyncio.run(main())

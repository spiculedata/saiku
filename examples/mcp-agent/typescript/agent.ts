/**
 * Saiku MCP sample agent (TypeScript).
 *
 * Connects to Saiku's native MCP server (streamable HTTP + Basic auth) and
 * either lets Claude drive the tool loop (ANTHROPIC_API_KEY set) or runs a
 * deterministic LLM-free smoke check (no key). See ../README.md.
 */

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import Anthropic from "@anthropic-ai/sdk";

const SAIKU_URL = (process.env.SAIKU_URL ?? "http://localhost:8080").replace(/\/$/, "");
const SAIKU_USER = process.env.SAIKU_USER ?? "admin";
const SAIKU_PASSWORD = process.env.SAIKU_PASSWORD ?? "admin";
const MCP_URL = `${SAIKU_URL}/rest/saiku/api/mcp`;
const QUESTION =
  process.env.QUESTION ??
  "What cubes exist in Saiku? Pick the most interesting one and show me " +
    "sales by product family (or the closest equivalent breakdown it offers).";

const BASIC_AUTH = "Basic " + Buffer.from(`${SAIKU_USER}:${SAIKU_PASSWORD}`).toString("base64");

/** Flatten an MCP tool result into plain text for Claude / stdout.
 *  Typed loosely because the SDK's callTool return is a union. */
function resultText(result: unknown): string {
  const content = (result as { content?: Array<{ type: string; text?: string }> }).content ?? [];
  const parts = content
    .filter((b) => b.type === "text" && typeof b.text === "string")
    .map((b) => b.text as string);
  return parts.length ? parts.join("\n") : "(empty result)";
}

/** LLM-free connectivity check: list -> describe -> run one example query. */
async function smoke(mcp: Client): Promise<void> {
  const { tools } = await mcp.listTools();
  console.log(`connected: ${tools.length} tools -> ${tools.map((t) => t.name).sort().join(", ")}\n`);

  const cubesRaw = resultText(await mcp.callTool({ name: "list_cubes", arguments: {} }));
  const cubesParsed = JSON.parse(cubesRaw);
  const cubes: Array<Record<string, string>> = Array.isArray(cubesParsed)
    ? cubesParsed
    : (cubesParsed.cubes ?? []);
  if (!cubes.length) throw new Error("no cubes visible to this user — check datasources / roles");
  // Prefer a sales-ish cube for a demo that shows real rows; else take the first.
  const first = cubes.find((c) => (c.cubeName ?? "").toLowerCase().includes("sales")) ?? cubes[0];
  const cubeId = [first.connectionName, first.catalog, first.schema, first.cubeName].join("/");
  console.log(`picked cube: ${cubeId}`);

  const schemaRaw = resultText(await mcp.callTool({ name: "describe_cube", arguments: { cube: cubeId } }));
  const schema = JSON.parse(schemaRaw);
  // measures/dimensions are objects keyed by lowercased name, not arrays.
  console.log(
    `described:  ${Object.keys(schema.measures ?? {}).length} measures, ` +
      `${Object.keys(schema.dimensions ?? {}).length} dimensions`,
  );

  // Every describe_cube response ships ready-made example bodies — run one
  // verbatim, so the smoke test never has to guess at valid names.
  const examples: Array<Record<string, unknown>> = schema.examples ?? [];
  if (!examples.length) {
    console.log("no example bodies on this cube — smoke check ends here (still OK)");
    return;
  }
  const body = (examples[0].request as Record<string, unknown>) ?? examples[0];
  const resultRaw = resultText(await mcp.callTool({ name: "run_query", arguments: body }));
  const result = JSON.parse(resultRaw);
  // Require an explicit SUCCESS status — a missing/blank status means the server didn't
  // confirm the query ran, so the smoke check must not silently pass on it.
  if (result.status !== "SUCCESS")
    throw new Error(`run_query failed: status=${JSON.stringify(result.status)}, error=${result.error}`);
  const rows = result.data ?? [];
  console.log(`ran example query: status=${result.status}, ${rows.length} data rows — smoke check PASSED`);
}

/** Claude drives the loop: Saiku's MCP tools become Claude tools. */
async function agent(mcp: Client): Promise<void> {
  const client = new Anthropic();
  const listed = await mcp.listTools();
  const tools: Anthropic.Tool[] = listed.tools.map((t) => ({
    name: t.name,
    description: t.description ?? t.name,
    input_schema: t.inputSchema as Anthropic.Tool.InputSchema,
  }));
  console.log(`agent mode: ${tools.length} Saiku tools handed to Claude`);
  console.log(`question:   ${QUESTION}\n`);

  const messages: Anthropic.MessageParam[] = [{ role: "user", content: QUESTION }];
  for (;;) {
    const response = await client.messages.create({
      model: "claude-opus-4-8",
      max_tokens: 16000,
      thinking: { type: "adaptive" },
      system:
        "You are a BI analyst working against a Saiku OLAP server via its " +
        "MCP tools. Discover what exists before querying: call list_cubes " +
        "first, then describe_cube for anything you intend to query — the " +
        "description includes valid measure/dimension names and ready-made " +
        "example bodies. If a call returns VALIDATION_ERROR, read its " +
        "`available` list and correct yourself. Answer with the actual " +
        "numbers, briefly.",
      tools,
      messages,
    });
    messages.push({ role: "assistant", content: response.content });

    if (response.stop_reason !== "tool_use") {
      const final = response.content
        .filter((b): b is Anthropic.TextBlock => b.type === "text")
        .map((b) => b.text)
        .join("");
      console.log("\n=== agent answer ===\n" + final);
      return;
    }

    const results: Anthropic.ToolResultBlockParam[] = [];
    for (const block of response.content) {
      if (block.type !== "tool_use") continue;
      console.log(`  -> ${block.name}(${JSON.stringify(block.input).slice(0, 120)}...)`);
      try {
        const out = await mcp.callTool({
          name: block.name,
          arguments: block.input as Record<string, unknown>,
        });
        results.push({
          type: "tool_result",
          tool_use_id: block.id,
          content: resultText(out),
          is_error: Boolean((out as { isError?: boolean }).isError),
        });
      } catch (e) {
        // surface transport errors to the model
        results.push({
          type: "tool_result",
          tool_use_id: block.id,
          content: `tool call failed: ${e instanceof Error ? e.message : String(e)}`,
          is_error: true,
        });
      }
    }
    // All results for one assistant turn go back in ONE user message.
    messages.push({ role: "user", content: results });
  }
}

async function main(): Promise<void> {
  console.log(`Saiku MCP endpoint: ${MCP_URL} (user: ${SAIKU_USER})`);
  const transport = new StreamableHTTPClientTransport(new URL(MCP_URL), {
    requestInit: { headers: { Authorization: BASIC_AUTH } },
  });
  const mcp = new Client({ name: "saiku-sample-agent", version: "1.0.0" });
  await mcp.connect(transport);
  try {
    if (process.env.ANTHROPIC_API_KEY) {
      await agent(mcp);
    } else {
      console.log("(no ANTHROPIC_API_KEY — running LLM-free smoke check)\n");
      await smoke(mcp);
    }
  } finally {
    await mcp.close();
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

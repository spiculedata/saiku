<script lang="ts">
  import { onMount } from "svelte";
  import { platform } from "$lib/stores/platform.svelte";

  interface Props {
    /** When true (default), section cards are collapsed by default — used by
     *  the login-page reveal so visitors aren't smacked with a wall of JSON.
     *  Inside the admin "API access" tab pass `defaultOpen` if you want it
     *  expanded out the gate. */
    defaultOpen?: boolean;
  }
  let { defaultOpen = false }: Props = $props();

  onMount(() => {
    if (!platform.capabilities) {
      platform.loadCapabilities();
    }
  });

  const baseUrl = $derived(typeof window === "undefined" ? "" : window.location.origin);

  function copy(value: string): void {
    if (typeof navigator !== "undefined" && navigator.clipboard) {
      navigator.clipboard.writeText(value);
    }
  }

  const sampleAiBody = JSON.stringify(
    {
      cube: "unknown_foodmart/FoodMart/FoodMart/Sales",
      measures: [{ name: "Store Sales" }],
      rows: [{ dimension: "Product", hierarchy: "Products", level: "Product Family" }],
    },
    null,
    2,
  );

  const mcpClientConfig = $derived(
    JSON.stringify(
      {
        mcpServers: {
          saiku: {
            transport: "streamable-http",
            url: platform.capabilities?.mcp?.url ?? `${baseUrl}/mcp`,
          },
        },
      },
      null,
      2,
    ),
  );
</script>

<div class="api-access">
  <header>
    <h2>Agent API access</h2>
    <p class="muted">
      Programmatic surfaces an LLM agent (or any HTTP client) can use against this Saiku.
      All endpoints accept HTTP Basic auth with your Saiku credentials.
    </p>
  </header>

  {#if !platform.capabilities}
    <p class="muted">Probing capabilities…</p>
  {:else}
    <details class="card" open={defaultOpen}>
      <summary>
        <span class="card__title">AI Query API</span>
        <span class="badge badge--ok">enabled</span>
        <span class="card__hint muted">REST · MDX-free</span>
      </summary>

      <p class="muted card__body">
        Typed REST surface designed for agents. Hierarchies, levels and measures are
        discoverable; a single <code>POST /ai/query</code> translates a JSON description
        into validated MDX, runs it, and returns typed <code>{`{value, formatted, unit}`}</code> cells.
        Validation failures carry a <code>{`{status, field, available}`}</code> envelope for self-correction.
      </p>

      <div class="kv">
        <div class="kv__row">
          <span class="kv__key">Base path</span>
          <code class="kv__val">{baseUrl}{platform.capabilities.ai.basePath}</code>
        </div>
        {#each Object.entries(platform.capabilities.ai.endpoints) as [name, path] (name)}
          <div class="kv__row">
            <span class="kv__key">{name}</span>
            <code class="kv__val">{baseUrl}{path}</code>
          </div>
        {/each}
      </div>

      <details class="recipe">
        <summary>Example: list cubes</summary>
        <pre><code>{`curl -u USERNAME:PASSWORD ${baseUrl}/rest/saiku/api/ai/cubes`}</code></pre>
      </details>

      <details class="recipe">
        <summary>Example: run a query</summary>
        <pre><code>{`curl -u USERNAME:PASSWORD \\
  -H 'Content-Type: application/json' \\
  -d '${sampleAiBody}' \\
  ${baseUrl}/rest/saiku/api/ai/query`}</code></pre>
      </details>

      <p class="muted small">
        Full reference: <code>docs/AI-QUERY-API.md</code> in the repo.
      </p>
    </details>

    <details class="card" open={defaultOpen}>
      <summary>
        <span class="card__title">Model Context Protocol (MCP)</span>
        {#if platform.capabilities.mcp.enabled}
          <span class="badge badge--ok">enabled</span>
        {:else}
          <span class="badge badge--off">not exposed</span>
        {/if}
        <span class="card__hint muted">Claude Desktop · Cursor · Cline</span>
      </summary>

      <p class="muted card__body">
        <code>saiku-mcp</code> wraps the AI Query API as an MCP server so Claude Desktop,
        Cursor, Cline and other agent hosts can wire to Saiku natively.
      </p>

      {#if platform.capabilities.mcp.enabled}
        <div class="install-row">
          <a class="btn btn--primary" href="/rest/saiku/info/mcp.dxt" download="saiku.dxt">
            Download <code>.dxt</code> for Claude Desktop
          </a>
          <p class="muted small">
            One-click install: drag <code>saiku.dxt</code> onto Claude Desktop / Cursor.
            The manifest carries this deployment's MCP URL — re-download if you move
            Saiku to a different host.
          </p>
        </div>

        <div class="kv">
          <div class="kv__row">
            <span class="kv__key">URL</span>
            <code class="kv__val">{platform.capabilities.mcp.url}</code>
            <button class="copy" onclick={() => copy(platform.capabilities!.mcp.url!)}>Copy</button>
          </div>
          <div class="kv__row">
            <span class="kv__key">Transport</span>
            <code class="kv__val">{platform.capabilities.mcp.transport}</code>
          </div>
        </div>

        <details class="recipe">
          <summary>Or paste the client config manually</summary>
          <pre><code>{mcpClientConfig}</code></pre>
          <button class="copy" onclick={() => copy(mcpClientConfig)}>Copy</button>
        </details>
      {:else}
        <p class="muted small">
          The container ships <code>saiku-mcp</code> as a stdio binary at
          <code>/usr/local/bin/saiku-mcp</code>. To expose it over HTTP, run a stdio↔HTTP
          bridge (e.g. <a href="https://github.com/sparfenyuk/mcp-proxy">mcp-proxy</a>) in
          front and start the launcher with
          <code>-Dsaiku.mcp.url=https://your-host/mcp</code> so this panel surfaces the URL.
        </p>
      {/if}
    </details>
  {/if}
</div>

<style>
  .api-access {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    max-width: 60rem;
  }
  .muted { color: var(--fg-muted); }
  .small { font-size: 0.85rem; }

  /* A <details> styled as a card. Summary is the accordion header. */
  .card {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-md, 0.5rem);
    overflow: hidden;
  }
  .card > summary {
    list-style: none;
    cursor: pointer;
    padding: var(--space-3) var(--space-4);
    display: flex;
    align-items: center;
    gap: var(--space-3);
    user-select: none;
  }
  /* Remove the default disclosure triangle and supply our own caret. */
  .card > summary::-webkit-details-marker { display: none; }
  .card > summary::before {
    content: "▸";
    color: var(--fg-muted);
    font-size: 0.85rem;
    transition: transform 0.15s ease;
  }
  .card[open] > summary::before { transform: rotate(90deg); }
  .card[open] > summary { border-bottom: 1px solid var(--border); }

  .card__title { font-weight: 600; }
  .card__hint { font-size: 0.8rem; margin-left: auto; }
  .card__body { padding: var(--space-3) var(--space-4) 0; margin-top: 0; }
  .card > .kv,
  .card > .recipe,
  .card > .install-row,
  .card > p {
    margin-left: var(--space-4);
    margin-right: var(--space-4);
  }
  .card > p.muted.small:last-of-type {
    padding-bottom: var(--space-3);
  }

  .badge {
    font-size: 0.7rem;
    padding: 0.15rem 0.5rem;
    border-radius: 999px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .badge--ok {
    background: var(--accent-soft, #d1fae5);
    color: var(--accent-strong, #047857);
  }
  .badge--off {
    background: var(--bg-muted);
    color: var(--fg-muted);
  }
  .kv {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    margin: var(--space-3) 0;
  }
  .kv__row {
    display: grid;
    grid-template-columns: 8rem 1fr auto;
    align-items: baseline;
    gap: var(--space-2);
  }
  .kv__key { color: var(--fg-muted); font-size: 0.85rem; }
  .kv__val {
    background: var(--bg-muted);
    padding: 0.1rem 0.4rem;
    border-radius: 0.25rem;
    font-size: 0.85rem;
    word-break: break-all;
  }
  .recipe { margin: var(--space-3) 0; }
  .recipe summary { cursor: pointer; user-select: none; }
  .recipe pre {
    background: var(--bg-muted);
    padding: var(--space-3);
    border-radius: var(--radius-sm, 0.25rem);
    overflow: auto;
    font-size: 0.8rem;
  }
  .copy {
    margin-left: var(--space-2);
    font-size: 0.75rem;
    padding: 0.15rem 0.5rem;
    background: var(--bg-muted);
    border: 1px solid var(--border);
    border-radius: 0.25rem;
    cursor: pointer;
  }
  .install-row {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    margin: var(--space-3) 0;
    padding: var(--space-3);
    background: var(--accent-soft, #ecfdf5);
    border-radius: var(--radius-sm, 0.25rem);
  }
  .install-row .btn {
    align-self: flex-start;
    text-decoration: none;
  }
</style>

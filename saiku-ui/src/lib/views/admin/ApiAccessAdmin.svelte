<script lang="ts">
	import { onMount } from 'svelte';
	import { buttonVariants } from '$lib/components/ui';
	import { platform } from '$lib/stores/platform.svelte';

	// Collapsed by default on the login page (where space is tight and a
	// first-time visitor shouldn't have to scroll past two big cards to
	// sign in); admin opens them so the operator sees connection info at
	// a glance.
	let { defaultOpen = false } = $props();

	onMount(() => {
		if (!platform.capabilities) {
			platform.loadCapabilities();
		}
	});

	const baseUrl = $derived(typeof window === 'undefined' ? '' : window.location.origin);

	function copy(value: string): void {
		if (typeof navigator !== 'undefined' && navigator.clipboard) {
			navigator.clipboard.writeText(value);
		}
	}

	const sampleAiBody = JSON.stringify(
		{
			cube: 'unknown_foodmart/FoodMart/FoodMart/Sales',
			measures: [{ name: 'Store Sales' }],
			rows: [{ dimension: 'Product', hierarchy: 'Products', level: 'Product Family' }]
		},
		null,
		2
	);

	const mcpClientConfig = $derived(
		JSON.stringify(
			{
				mcpServers: {
					saiku: {
						transport: 'streamable-http',
						url: platform.capabilities?.mcp?.url ?? `${baseUrl}/mcp`
					}
				}
			},
			null,
			2
		)
	);
</script>

<div class="api-access">
	<header>
		<h2>Agent API access</h2>
		<p class="text-fg-muted">
			Programmatic surfaces an LLM agent (or any HTTP client) can use against this Saiku. All
			endpoints accept HTTP Basic auth with your Saiku credentials.
		</p>
	</header>

	{#if !platform.capabilities}
		<p class="text-fg-muted">Probing capabilities…</p>
	{:else}
		<details class="card" open={defaultOpen}>
			<summary>
				<h3>
					AI Query API
					<span class="badge bg-success-soft text-success-strong">enabled</span>
				</h3>
			</summary>
			<p class="text-fg-muted">
				Typed REST surface designed for agents. Hierarchies, levels and measures are discoverable; a
				single <code>POST /ai/query</code> translates a JSON description into validated MDX, runs
				it, and returns typed <code>{`{value, formatted, unit}`}</code> cells. Validation failures
				carry a <code>{`{status, field, available}`}</code> envelope for self-correction.
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
				<pre><code
						>{`curl -u USERNAME:PASSWORD \\
  -H 'Content-Type: application/json' \\
  -d '${sampleAiBody}' \\
  ${baseUrl}/rest/saiku/api/ai/query`}</code
					></pre>
			</details>

			<p class="small text-fg-muted">
				Full reference: <code>docs/AI-QUERY-API.md</code> in the repo.
			</p>
		</details>

		<details class="card" open={defaultOpen}>
			<summary>
				<h3>
					Model Context Protocol (MCP)
					{#if platform.capabilities.mcp.enabled}
						<span class="badge bg-success-soft text-success-strong">enabled</span>
					{:else}
						<span class="badge bg-bg-muted text-fg-muted">not exposed</span>
					{/if}
				</h3>
			</summary>
			<p class="text-fg-muted">
				<code>saiku-mcp</code> wraps the AI Query API as an MCP server so Claude Desktop, Cursor, Cline
				and other agent hosts can wire to Saiku natively.
			</p>

			{#if platform.capabilities.mcp.enabled}
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

				<div class="install-row">
					<strong>One-click install for Claude Desktop / Cursor</strong>
					<span class="small text-fg-muted">
						Downloads a <code>.dxt</code> bundle wired to this server's MCP URL. Drag it into Claude Desktop
						(or open with Cursor) to register the agent.
					</span>
					<a class={buttonVariants()} href="/rest/saiku/info/mcp.dxt" download="saiku.dxt">
						Download saiku.dxt
					</a>
				</div>

				<details class="recipe">
					<summary>Client config (Claude Desktop, Cursor, Cline)</summary>
					<pre><code>{mcpClientConfig}</code></pre>
					<button class="copy" onclick={() => copy(mcpClientConfig)}>Copy</button>
				</details>
			{:else}
				<p class="small text-fg-muted">
					The container ships <code>saiku-mcp</code> as a stdio binary at
					<code>/usr/local/bin/saiku-mcp</code>. To expose it over HTTP, run a stdio↔HTTP bridge
					(e.g. <a href="https://github.com/sparfenyuk/mcp-proxy">mcp-proxy</a>) in front and start
					the launcher with
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
		gap: var(--space-5);
		max-width: 60rem;
	}
	.small {
		font-size: 0.85rem;
	}
	.card {
		background: hsl(var(--bg));
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-md, 0.5rem);
		padding: var(--space-4);
	}
	.card > summary {
		cursor: pointer;
		user-select: none;
		list-style: none;
	}
	.card > summary::-webkit-details-marker {
		display: none;
	}
	.card > summary::before {
		content: '▸';
		display: inline-block;
		margin-right: var(--space-2);
		transition: transform 120ms ease;
		color: hsl(var(--fg-muted));
	}
	.card[open] > summary::before {
		transform: rotate(90deg);
	}
	/* The summary owns the click target; the heading itself stays inline
   * with the badge but loses its bottom margin when collapsed so the
   * card hugs the title. */
	.card > summary h3 {
		display: inline-flex;
		align-items: center;
		gap: var(--space-2);
		margin: 0;
	}
	.card[open] > summary {
		margin-bottom: var(--space-3);
	}
	.badge {
		font-size: 0.7rem;
		padding: 0.15rem 0.5rem;
		border-radius: 999px;
		text-transform: uppercase;
		letter-spacing: 0.04em;
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
	.kv__key {
		color: hsl(var(--fg-muted));
		font-size: 0.85rem;
	}
	.kv__val {
		background: hsl(var(--bg-muted));
		padding: 0.1rem 0.4rem;
		border-radius: 0.25rem;
		font-size: 0.85rem;
		word-break: break-all;
	}
	.recipe {
		margin: var(--space-3) 0;
	}
	.recipe summary {
		cursor: pointer;
		user-select: none;
	}
	.recipe pre {
		background: hsl(var(--bg-muted));
		padding: var(--space-3);
		border-radius: var(--radius-sm, 0.25rem);
		overflow: auto;
		font-size: 0.8rem;
	}
	.copy {
		margin-left: var(--space-2);
		font-size: 0.75rem;
		padding: 0.15rem 0.5rem;
		background: hsl(var(--bg-muted));
		border: 1px solid hsl(var(--border));
		border-radius: 0.25rem;
		cursor: pointer;
	}
	.install-row {
		display: flex;
		flex-direction: column;
		gap: var(--space-2);
		margin: var(--space-3) 0;
		padding: var(--space-3);
		background: hsl(var(--accent));
		border-radius: var(--radius-sm);
	}
	.install-row .btn {
		align-self: flex-start;
		text-decoration: none;
	}
</style>

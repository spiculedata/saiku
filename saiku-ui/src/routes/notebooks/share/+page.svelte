<script lang="ts">
	/*
	 * #1108 notebook share viewer — public, account-free, read-only notebook
	 * page at /ui/notebooks/share#<token>. Mirrors routes/share/+page.svelte
	 * (the #941 dashboard viewer): the token lives in the URL fragment (never
	 * sent to the server), read from window.location.hash and echoed as the
	 * X-Saiku-Share-Token header to the guest endpoints. Every MDX cell's
	 * query is prefetched up front and handed to MdxCell as `initialResult`
	 * so the read-only render never tries to call the normal (session-gated)
	 * query path a guest can't reach.
	 */
	import { onMount } from 'svelte';
	import { parseShareToken } from '$lib/dashboard/shareUrl';
	import {
		loadSharedNotebook,
		runSharedNotebookCellQuery,
		type Notebook
	} from '$lib/api/notebooks';
	import type { QueryResult } from '$lib/api/query';
	import MarkdownCell from '$lib/views/notebook/MarkdownCell.svelte';
	import MdxCell from '$lib/views/notebook/MdxCell.svelte';

	let loading = $state(true);
	let error = $state<string | null>(null);
	let notebook = $state<Notebook | null>(null);
	let results = $state<Record<string, QueryResult>>({});

	onMount(async () => {
		const token = parseShareToken(window.location.hash);
		if (!token) {
			error = 'This share link is missing its access token.';
			loading = false;
			return;
		}
		try {
			const nb = await loadSharedNotebook(token);
			notebook = nb;
			for (const cell of nb.cells) {
				if (cell.type === 'mdx') {
					try {
						results[cell.id] = await runSharedNotebookCellQuery(token, cell.id);
					} catch {
						// leave it unset — the cell renders no result grid
					}
				}
			}
		} catch {
			error = 'This share link is invalid or has expired.';
		} finally {
			loading = false;
		}
	});
</script>

<svelte:head><title>{notebook?.name || 'Shared notebook'} — Saiku</title></svelte:head>

<div class="share-view">
	{#if loading}
		<div class="share-view__state">Loading shared notebook…</div>
	{:else if error}
		<div class="share-view__state text-danger">{error}</div>
	{:else if notebook}
		<header
			class="flex items-center justify-between gap-3 border-b border-border bg-bg-muted px-6 py-3"
		>
			<span class="text-lg font-bold text-fg">{notebook.name}</span>
			<span class="share-view__badge">Read-only shared view</span>
		</header>
		<div class="share-view__cells">
			{#each notebook.cells as cell (cell.id)}
				{#if cell.type === 'markdown'}
					<MarkdownCell markdown={cell.markdown ?? ''} readOnly />
				{:else}
					<MdxCell
						mdx={cell.mdx ?? ''}
						cube={cell.cube}
						readOnly
						initialResult={results[cell.id] ?? null}
					/>
				{/if}
			{/each}
		</div>
	{/if}
</div>

<style>
	.share-view {
		flex: 1;
		min-height: 0;
		display: flex;
		flex-direction: column;
		background: hsl(var(--bg));
	}
	.share-view__badge {
		font-size: var(--fs-sm);
		color: hsl(var(--fg-muted));
		padding: 2px var(--space-2);
		border: 1px solid hsl(var(--border));
		border-radius: 999px;
		background: hsl(var(--bg-subtle));
	}
	.share-view__state {
		flex: 1;
		display: flex;
		align-items: center;
		justify-content: center;
		color: hsl(var(--fg-muted));
		font-size: var(--fs-md);
	}
	.share-view__cells {
		flex: 1;
		min-height: 0;
		overflow: auto;
		max-width: 960px;
		margin: 0 auto;
		padding: 1rem 1.5rem 4rem;
		width: 100%;
		box-sizing: border-box;
	}
</style>

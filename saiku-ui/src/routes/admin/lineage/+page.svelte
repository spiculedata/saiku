<!--
  Admin › Measure Lineage — saiku#1120 Phase 1. "If I deprecate this measure, what
  breaks?" Lists every dashboard, saved query and calculated measure that references
  a given measure / dimension / hierarchy / level unique name, grouped by kind.

  Static-source scan only (dashboards, saved queries, schema calculated members).
  Ad-hoc query lineage via the AI audit log and the reverse view ("pick a dashboard,
  see every measure it uses") are later phases per the issue.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { Button, Input } from '$lib/components/ui';
	import { adminLineage, type LineageDependent } from '$lib/api/admin';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { groupByKind } from './groupByKind';

	let query = $state('');
	let loading = $state(false);
	let searched = $state(false);
	let results = $state<LineageDependent[]>([]);

	const grouped = $derived(groupByKind(results));

	async function search() {
		const trimmed = query.trim();
		if (!trimmed) return;
		loading = true;
		try {
			results = await adminLineage.find(trimmed);
			searched = true;
			void goto(`/admin/lineage?measure=${encodeURIComponent(trimmed)}`, {
				replaceState: true,
				keepFocus: true,
				noScroll: true
			});
		} catch (e) {
			toasts.danger('Could not load lineage', e instanceof Error ? e.message : String(e));
		} finally {
			loading = false;
		}
	}

	function onSubmit(event: SubmitEvent) {
		event.preventDefault();
		void search();
	}

	function fmtTime(ms: number): string {
		if (!ms) return '—';
		return new Date(ms).toLocaleString();
	}

	onMount(() => {
		const fromUrl = page.url.searchParams.get('measure');
		if (fromUrl) {
			query = fromUrl;
			void search();
		}
	});
</script>

<div class="pane">
	<header>
		<h2>Measure lineage</h2>
		<p class="text-sm text-fg-muted">
			Find every dashboard, saved query and calculated measure that references a measure, dimension,
			hierarchy or level — e.g. <code>[Measures].[Store Sales]</code> or
			<code>[Store].[Stores].[Store Country]</code>.
		</p>
	</header>

	<form class="search-row" onsubmit={onSubmit}>
		<Input
			bind:value={query}
			placeholder="[Measures].[Store Sales]"
			aria-label="Measure, dimension, hierarchy or level unique name"
		/>
		<Button type="submit" disabled={loading || !query.trim()}>
			{loading ? 'Searching…' : 'Find dependents'}
		</Button>
	</form>

	{#if searched && !loading}
		{#if results.length === 0}
			<p class="text-sm text-fg-muted">
				No dashboards, saved queries or calculated measures reference this.
			</p>
		{:else}
			<p class="text-sm text-fg-muted">
				{results.length} dependent{results.length === 1 ? '' : 's'} found.
			</p>
			{#each grouped as group (group.kind)}
				<section class="flex flex-col gap-2">
					<h3>
						{group.label}
						<span class="text-sm text-fg-muted">({group.items.length})</span>
					</h3>
					<div class="overflow-auto rounded-sm border border-border">
						<table>
							<thead>
								<tr>
									<th>Name</th>
									<th>Path</th>
									<th>Last modified</th>
								</tr>
							</thead>
							<tbody>
								{#each group.items as item (item.path)}
									<tr>
										<td>{item.name}</td>
										<td class="text-fg-muted">{item.path}</td>
										<td>{fmtTime(item.lastModified)}</td>
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
				</section>
			{/each}
		{/if}
	{/if}
</div>

<style>
	.pane {
		display: flex;
		flex-direction: column;
		gap: var(--space-4);
		padding: var(--space-4);
		max-width: 960px;
	}
	h2 {
		margin: 0 0 var(--space-1);
	}
	h3 {
		margin: 0 0 var(--space-2);
		font-size: var(--fs-sm);
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: hsl(var(--fg-muted));
	}
	.search-row {
		display: flex;
		gap: var(--space-2);
		align-items: center;
	}
	.search-row :global(input) {
		flex: 1;
	}
	table {
		width: 100%;
		border-collapse: collapse;
		font-size: var(--fs-sm);
	}
	th,
	td {
		padding: 6px 10px;
		text-align: left;
		border-bottom: 1px solid hsl(var(--border));
	}
	th {
		background: hsl(var(--bg-muted));
		font-weight: var(--weight-semibold);
	}
	tr:last-child td {
		border-bottom: 0;
	}
</style>

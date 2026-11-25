<script lang="ts">
	import { onMount } from 'svelte';
	import MonacoEditor from '$lib/components/MonacoEditor.svelte';
	import { Button, Select } from '$lib/components/ui';
	import { FormField, FeedbackBanner, PageHeader } from '$lib/design-system';
	import { i18n } from '$lib/stores/i18n.svelte';
	import {
		listDatasources,
		runQuery,
		datasourceInfo,
		listTables,
		selectAllSql,
		SqlWorkbenchApiError,
		type SqlDatasource,
		type SqlDatasourceInfo,
		type SqlQueryResult
	} from '$lib/api/sqlWorkbench';
	import { sqlResultToCsv } from '$lib/sqlworkbench/csv';
	import { downloadCsv } from '$lib/ossie/exportCsv';

	/** Client-side pager over the (server-capped) result set — no shared pagination
	 *  component exists yet in saiku-ui, so this stays local rather than inventing one. */
	const PAGE_SIZE = 50;

	let datasources = $state<SqlDatasource[]>([]);
	let datasourcesError = $state<string | null>(null);
	let selectedDatasource = $state('');
	let sql = $state('SELECT 1');
	let running = $state(false);
	let result = $state<SqlQueryResult | null>(null);
	let queryError = $state<string | null>(null);
	let page = $state(0);

	// saiku#2599 — what the selected datasource actually connects to, and its default-schema
	// tables. Refetched whenever the selection changes so the selector's effect is visible
	// even when several datasources share one warehouse.
	let dsInfo = $state<SqlDatasourceInfo | null>(null);
	let dsInfoError = $state(false);
	let tables = $state<string[]>([]);
	let tablesLoading = $state(false);
	let tablesOpen = $state(false);

	onMount(async () => {
		try {
			datasources = await listDatasources();
			if (datasources.length > 0) selectedDatasource = datasources[0].name;
		} catch (e) {
			datasourcesError = e instanceof Error ? e.message : 'Could not load datasources';
		}
	});

	$effect(() => {
		const ds = selectedDatasource;
		// Only `selectedDatasource` is tracked here (the async body's writes happen after the
		// first await, outside the effect's tracking pass — the Svelte 5 re-queue trap).
		void loadDatasourceMeta(ds);
	});

	async function loadDatasourceMeta(ds: string): Promise<void> {
		dsInfo = null;
		dsInfoError = false;
		tables = [];
		tablesOpen = false;
		if (!ds) return;
		tablesLoading = true;
		try {
			const [info, names] = await Promise.all([datasourceInfo(ds), listTables(ds)]);
			if (selectedDatasource !== ds) return; // the user moved on — drop the stale answer
			dsInfo = info;
			tables = names;
		} catch {
			if (selectedDatasource === ds) dsInfoError = true;
		} finally {
			tablesLoading = false;
		}
	}

	function insertTable(table: string): void {
		sql = selectAllSql(table);
	}

	let totalPages = $derived(result ? Math.max(1, Math.ceil(result.rows.length / PAGE_SIZE)) : 1);
	let pageRows = $derived(
		result ? result.rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE) : []
	);

	async function run(): Promise<void> {
		if (!selectedDatasource || !sql.trim() || running) return;
		running = true;
		queryError = null;
		try {
			result = await runQuery(selectedDatasource, sql);
			page = 0;
		} catch (e) {
			result = null;
			queryError =
				e instanceof SqlWorkbenchApiError
					? e.message
					: e instanceof Error
						? e.message
						: 'The query failed';
		} finally {
			running = false;
		}
	}

	function exportCsv(): void {
		if (!result || result.rows.length === 0) return;
		downloadCsv(`${selectedDatasource || 'query'}.csv`, sqlResultToCsv(result));
	}

	function rowsShownLabel(r: SqlQueryResult): string {
		return i18n
			.t('sqlWorkbench.rowsShown', '{rows} row(s) in {ms} ms')
			.replace('{rows}', String(r.rowCount))
			.replace('{ms}', String(r.durationMs));
	}

	function truncatedLabel(r: SqlQueryResult): string {
		return i18n
			.t('sqlWorkbench.truncated', 'Showing the first {rows} rows — the query returned more.')
			.replace('{rows}', String(r.rowCount));
	}

	function pageLabel(): string {
		return i18n
			.t('sqlWorkbench.page', 'Page {page} of {total}')
			.replace('{page}', String(page + 1))
			.replace('{total}', String(totalPages));
	}
</script>

<div class="sql-workbench">
	<PageHeader title={i18n.t('sqlWorkbench.title')} subtitle={i18n.t('sqlWorkbench.subtitle')} />

	{#if datasourcesError}
		<FeedbackBanner tone="error">{datasourcesError}</FeedbackBanner>
	{/if}

	<div class="sql-workbench__toolbar">
		<FormField label={i18n.t('sqlWorkbench.datasource')}>
			<Select bind:value={selectedDatasource} aria-label={i18n.t('sqlWorkbench.datasource')}>
				<option value="" disabled>{i18n.t('sqlWorkbench.datasource.placeholder')}</option>
				{#each datasources as ds (ds.name)}
					<option value={ds.name}>{ds.name}</option>
				{/each}
			</Select>
		</FormField>
		<Button onclick={run} disabled={running || !selectedDatasource || !sql.trim()}>
			{running ? i18n.t('sqlWorkbench.running') : i18n.t('sqlWorkbench.run')}
		</Button>
		<Button variant="outline" onclick={exportCsv} disabled={!result || result.rows.length === 0}>
			{i18n.t('sqlWorkbench.exportCsv')}
		</Button>
	</div>

	{#if selectedDatasource}
		<p class="sql-workbench__ds-info" data-testid="ds-info">
			{#if dsInfo}
				<span class="sql-workbench__ds-target">
					{dsInfo.name} → {dsInfo.databaseProductName ?? '?'} · {dsInfo.catalog ??
						'?'}.{dsInfo.schema ?? '?'}
				</span>
				{#if dsInfo.url}
					<span class="sql-workbench__ds-url" title={dsInfo.url}>{dsInfo.url}</span>
				{/if}
			{:else if dsInfoError}
				<span class="sql-workbench__ds-missing">
					{i18n.t(
						'sqlWorkbench.infoUnavailable',
						'Could not load what this datasource connects to.'
					)}
				</span>
			{/if}
		</p>
	{/if}

	{#if tables.length > 0 || tablesLoading}
		<details class="sql-workbench__tables" bind:open={tablesOpen} data-testid="table-list">
			<summary>
				{i18n.t('sqlWorkbench.tables', 'Tables')}
				{#if tablesLoading}
					<span class="sql-workbench__tables-loading">…</span>
				{:else}
					({tables.length})
				{/if}
			</summary>
			<ul class="sql-workbench__tables-list">
				{#each tables as t (t)}
					<li>
						<button
							type="button"
							class="sql-workbench__table-name"
							data-table={t}
							onclick={() => insertTable(t)}
							title={selectAllSql(t)}
						>
							{t}
						</button>
					</li>
				{/each}
			</ul>
		</details>
	{/if}

	<p class="sql-workbench__notice">{i18n.t('sqlWorkbench.readOnlyNotice')}</p>

	<MonacoEditor value={sql} language="sql" minHeight="200px" onChange={(v) => (sql = v)} />

	{#if queryError}
		<FeedbackBanner tone="error">{queryError}</FeedbackBanner>
	{/if}

	{#if result}
		<div class="sql-workbench__meta">
			<span>{rowsShownLabel(result)}</span>
			{#if result.truncated}
				<span class="sql-workbench__truncated">{truncatedLabel(result)}</span>
			{/if}
		</div>
		{#if result.rows.length === 0}
			<p class="sql-workbench__empty">{i18n.t('sqlWorkbench.empty')}</p>
		{:else}
			<div class="sql-workbench__grid-wrap" data-testid="results-grid">
				<table class="data-grid">
					<thead>
						<tr>
							{#each result.columns as col (col)}
								<th>{col}</th>
							{/each}
						</tr>
					</thead>
					<tbody>
						{#each pageRows as row, i (i)}
							<tr>
								{#each row as cell, j (j)}
									<td>{cell ?? ''}</td>
								{/each}
							</tr>
						{/each}
					</tbody>
				</table>
			</div>
			{#if totalPages > 1}
				<div class="sql-workbench__pager">
					<Button variant="outline" size="sm" disabled={page === 0} onclick={() => page--}>
						{i18n.t('sqlWorkbench.prev')}
					</Button>
					<span>{pageLabel()}</span>
					<Button
						variant="outline"
						size="sm"
						disabled={page >= totalPages - 1}
						onclick={() => page++}
					>
						{i18n.t('sqlWorkbench.next')}
					</Button>
				</div>
			{/if}
		{/if}
	{:else}
		<p class="sql-workbench__empty">{i18n.t('sqlWorkbench.empty')}</p>
	{/if}
</div>

<style>
	.sql-workbench {
		display: flex;
		flex-direction: column;
		gap: 1rem;
		width: 100%;
		max-width: 1100px;
		margin: 0 auto;
		padding: 1.5rem;
		/* saiku#2599 C1: the page itself is the scroll container (`.app` is overflow:hidden and
	   `<main>` never scrolls), so a result taller than the viewport is reachable and the
	   pager stays reachable by scrolling. */
		height: 100%;
		overflow-y: auto;
	}
	.sql-workbench__toolbar {
		display: flex;
		align-items: flex-end;
		gap: 0.75rem;
		flex-wrap: wrap;
	}
	/* saiku#2599 C2: the selector's effect, spelled out — product · catalog.schema, plus the
   credential-redacted JDBC URL as secondary text. */
	.sql-workbench__ds-info {
		margin: 0;
		display: flex;
		align-items: baseline;
		gap: 0.75rem;
		flex-wrap: wrap;
		font-size: 0.8125rem;
		color: hsl(var(--fg-muted));
	}
	.sql-workbench__ds-target {
		color: hsl(var(--fg));
		font-weight: 500;
	}
	.sql-workbench__ds-url {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		display: inline-block;
		max-width: 100%;
		font-family: var(--font-mono, monospace);
	}
	.sql-workbench__ds-missing {
		font-style: italic;
	}
	/* saiku#2599 C3: the default schema's tables, collapsed by default. */
	.sql-workbench__tables {
		border: 1px solid hsl(var(--border));
		border-radius: 0.5rem;
		padding: 0.5rem 0.75rem;
		align-self: flex-start;
		min-width: 260px;
		max-width: 100%;
	}
	.sql-workbench__tables summary {
		cursor: pointer;
		font-size: 0.8125rem;
		font-weight: 500;
		color: hsl(var(--fg-muted));
		user-select: none;
	}
	.sql-workbench__tables-loading {
		color: hsl(var(--fg-muted));
	}
	.sql-workbench__tables-list {
		margin: 0.5rem 0 0;
		padding: 0;
		list-style: none;
		max-height: 16rem;
		overflow-y: auto;
		display: flex;
		flex-direction: column;
		gap: 0.125rem;
	}
	.sql-workbench__table-name {
		all: unset;
		cursor: pointer;
		padding: 0.25rem 0.375rem;
		border-radius: 0.25rem;
		font-size: 0.8125rem;
		font-family: var(--font-mono, monospace);
	}
	.sql-workbench__table-name:hover {
		background: hsl(var(--bg-subtle));
	}
	/* saiku#2599 C1: a wide result scrolls horizontally inside its own wrapper instead of
   being clipped by `.app`'s overflow:hidden. */
	.sql-workbench__grid-wrap {
		overflow-x: auto;
		max-width: 100%;
	}
	.sql-workbench__grid-wrap :global(table.data-grid) {
		min-width: max-content;
	}
	/* The column headers stay visible while the page (this container's nearest scrolling
   ancestor) scrolls the rows underneath; the th background comes from .data-grid. */
	.sql-workbench__grid-wrap :global(table.data-grid th) {
		position: sticky;
		top: 0;
		z-index: 1;
	}
	.sql-workbench__notice {
		margin: 0;
		font-size: 0.8125rem;
		color: hsl(var(--fg-muted));
	}
	.sql-workbench__meta {
		display: flex;
		align-items: center;
		gap: 1rem;
		font-size: 0.8125rem;
		color: hsl(var(--fg-muted));
	}
	.sql-workbench__truncated {
		color: hsl(var(--warning-fg, var(--fg-muted)));
	}
	.sql-workbench__empty {
		padding: 2rem 0;
		color: hsl(var(--fg-muted));
		text-align: center;
	}
	.sql-workbench__pager {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 0.75rem;
	}
</style>

<script lang="ts">
	import { onMount } from 'svelte';
	import MonacoEditor from '$lib/components/MonacoEditor.svelte';
	import { Button, Select } from '$lib/components/ui';
	import { FormField, FeedbackBanner, PageHeader } from '$lib/design-system';
	import { i18n } from '$lib/stores/i18n.svelte';
	import {
		listDatasources,
		runQuery,
		SqlWorkbenchApiError,
		type SqlDatasource,
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

	onMount(async () => {
		try {
			datasources = await listDatasources();
			if (datasources.length > 0) selectedDatasource = datasources[0].name;
		} catch (e) {
			datasourcesError = e instanceof Error ? e.message : 'Could not load datasources';
		}
	});

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
	}
	.sql-workbench__toolbar {
		display: flex;
		align-items: flex-end;
		gap: 0.75rem;
		flex-wrap: wrap;
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

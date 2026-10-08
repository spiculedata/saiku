<script lang="ts">
	/*
	 * Notebook MDX cell (issue #1108, phase 1): a cube selector, a Monaco MDX
	 * editor (same "mdx" Monaco language mode + component as the workspace's
	 * MDXModal), a Run button, and the result rendered in-cell as a grid.
	 *
	 * Each cell owns its cube selection independently — no shared workspace
	 * "selection" store — so multiple MDX cells in one notebook never step on
	 * each other (per the issue's "Each MDX cell has its own cube selector
	 * and an independent execution").
	 */
	import { datasources } from '$lib/stores/datasources.svelte';
	import { session } from '$lib/stores/session.svelte';
	import type { SaikuCube } from '$lib/api/discover';
	import { runNotebookCell, type NotebookCubeRef } from '$lib/api/notebooks';
	import type { QueryResult } from '$lib/api/query';
	import MonacoEditor from '$lib/components/MonacoEditor.svelte';
	import NotebookResultGrid from '$lib/views/notebook/NotebookResultGrid.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { Play } from '@lucide/svelte';

	interface Props {
		mdx: string;
		cube: NotebookCubeRef | undefined;
		readOnly?: boolean;
		onChange?: (mdx: string, cube: NotebookCubeRef | undefined) => void;
		/** Pre-fetched result for the share viewer (issue #1108): a guest has
		 *  no query-execution access, so the share route runs every MDX cell
		 *  once via the guest surface and passes the result in rather than
		 *  letting this component call run() itself. Ignored when set
		 *  alongside `readOnly={false}`. */
		initialResult?: QueryResult | null;
	}

	let { mdx, cube, readOnly = false, onChange, initialResult = null }: Props = $props();

	let running = $state(false);
	let result = $state<QueryResult | null>(readOnly ? initialResult : null);

	$effect(() => {
		const username = session.current?.username;
		if (username && !datasources.loaded && !datasources.loading && !datasources.error) {
			datasources.load(username);
		}
	});

	function refKey(ref: NotebookCubeRef): string {
		return `${ref.connectionName}/${ref.catalog}/${ref.schema}/${ref.cubeName}`;
	}

	function toRef(c: SaikuCube): NotebookCubeRef {
		return { connectionName: c.connection, catalog: c.catalog, schema: c.schema, cubeName: c.name };
	}

	// Flat list of every cube across every connection, for the picker and
	// for resolving the stored ref back to a caption to display.
	let allCubes = $derived(() => {
		const out: SaikuCube[] = [];
		for (const conn of datasources.connections) {
			for (const cat of conn.catalogs) {
				for (const sch of cat.schemas) {
					out.push(...sch.cubes);
				}
			}
		}
		return out;
	});

	let selectedCaption = $derived(() => {
		if (!cube) return '';
		const match = allCubes().find((c) => refKey(toRef(c)) === refKey(cube));
		return match ? match.caption || match.name : cube.cubeName;
	});

	function onCubeChange(e: Event) {
		const key = (e.currentTarget as HTMLSelectElement).value;
		const next = key ? allCubes().find((c) => refKey(toRef(c)) === key) : undefined;
		onChange?.(mdx, next ? toRef(next) : undefined);
	}

	function onMdxChange(value: string) {
		onChange?.(value, cube);
	}

	async function run() {
		if (!cube || !mdx.trim() || running) return;
		running = true;
		try {
			result = await runNotebookCell(mdx, cube);
		} catch (e) {
			result = { cellset: [], error: e instanceof Error ? e.message : String(e) };
		} finally {
			running = false;
		}
	}
</script>

<div class="mdx-cell">
	<div class="mdx-cell__toolbar">
		{#if readOnly}
			<span class="mdx-cell__cube">{selectedCaption()}</span>
		{:else}
			<select
				class="mdx-cell__cube-select"
				value={cube ? refKey(cube) : ''}
				onchange={onCubeChange}
			>
				<option value="">{i18n.t('notebook.cell.selectCube', 'Select a cube…')}</option>
				{#each allCubes() as c (refKey(toRef(c)))}
					<option value={refKey(toRef(c))}>{c.caption || c.name}</option>
				{/each}
			</select>
			<button
				type="button"
				class="mdx-cell__run"
				disabled={!cube || !mdx.trim() || running}
				onclick={run}
			>
				<Play size={14} />
				{running ? i18n.t('notebook.cell.running', 'Running…') : i18n.t('notebook.cell.run', 'Run')}
			</button>
		{/if}
	</div>
	{#if !readOnly}
		<MonacoEditor
			value={mdx}
			language="mdx"
			minHeight="120px"
			onChange={onMdxChange}
			readOnly={false}
		/>
	{:else}
		<pre class="mdx-cell__ro-mdx">{mdx}</pre>
	{/if}
	{#if result}
		<div class="mdx-cell__result">
			<NotebookResultGrid {result} />
		</div>
	{/if}
</div>

<style>
	.mdx-cell {
		padding: 0.5rem 0.25rem;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.mdx-cell__toolbar {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.mdx-cell__cube-select {
		font-size: var(--fs-sm);
		padding: 2px 6px;
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-md, 6px);
		background: hsl(var(--bg));
		color: hsl(var(--fg));
		max-width: 320px;
	}
	.mdx-cell__cube {
		font-size: var(--fs-sm);
		color: hsl(var(--fg-muted));
	}
	.mdx-cell__run {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-size: var(--fs-sm);
		padding: 3px 10px;
		border-radius: var(--radius-md, 6px);
		border: 1px solid hsl(var(--primary));
		background: hsl(var(--primary));
		color: hsl(var(--primary-foreground, white));
		cursor: pointer;
	}
	.mdx-cell__run:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
	.mdx-cell__ro-mdx {
		font-family: var(--font-mono, monospace);
		font-size: var(--fs-sm);
		background: hsl(var(--bg-subtle));
		padding: 0.5rem;
		border-radius: var(--radius-md, 6px);
		white-space: pre-wrap;
		margin: 0;
	}
	.mdx-cell__result {
		margin-top: 4px;
	}
</style>

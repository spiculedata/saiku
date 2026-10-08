<script lang="ts">
	/*
	 * MDX workbench (saiku#1106) — a dedicated /workbench route for the MDX
	 * editing experience that used to be trapped behind the toolbar's MDXModal.
	 *
	 * Deliberately keeps its cube selection and query buffer as LOCAL state
	 * rather than reading/writing the workspace singletons (`selection`,
	 * `query` in $lib/stores) — this is a standalone page a user can have open
	 * alongside the main workspace, and mutating those shared stores from here
	 * would silently change what the *other* tab/page is showing. The only
	 * global stores touched are read-mostly caches (`datasources`,
	 * `repository`) that are already safe to share across pages.
	 *
	 * Phase 1 (route + editor + reused grid), phase 2 (cube-grounded
	 * autocomplete via mdx-lang.ts), and phase 3 (history + save-as-query) all
	 * land together here — see saiku#1106 for the phase breakdown.
	 */
	import { RotateCw, Play, Save, History, Trash2 } from '@lucide/svelte';
	import { Button } from '$lib/components/ui';
	import MonacoEditor from '$lib/components/MonacoEditor.svelte';
	import MdxResultGrid from '$lib/views/MdxResultGrid.svelte';
	import SaveQueryModal from '$lib/modals/SaveQueryModal.svelte';
	import { session } from '$lib/stores/session.svelte';
	import { datasources, cubeKey } from '$lib/stores/datasources.svelte';
	import { repository } from '$lib/stores/repository.svelte';
	import { saveResource } from '$lib/api/repository';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { mdxHistory, type MdxHistoryEntry } from '$lib/stores/mdxHistory.svelte';
	import { newQuery, executeQuery, type QueryResult } from '$lib/api/query';
	import type { SaikuCube } from '$lib/api/discover';

	let selectedCube = $state<SaikuCube | null>(null);
	let mdxBuffer = $state<string>('');
	let running = $state(false);
	let result = $state<QueryResult | null>(null);
	let saveOpen = $state(false);
	let historyOpen = $state(true);

	$effect(() => {
		const username = session.current?.username;
		if (username && !datasources.loaded && !datasources.loading && !datasources.error) {
			datasources.load(username);
		}
	});

	// Cube-grounded autocomplete (phase 2): keep mdx-lang.ts's completion
	// context in sync with whichever cube is selected here. Dynamic import
	// because monaco-editor (and this module, transitively) must stay out of
	// the initial bundle — MonacoEditor.svelte follows the same pattern.
	$effect(() => {
		const cube = selectedCube;
		const username = session.current?.username;
		if (!cube || !username) return;
		datasources
			.metadata(username, cube)
			.then((md) =>
				import('$lib/monaco/mdx-lang').then(({ setMdxCompletionContext }) =>
					setMdxCompletionContext({ measures: md.measures, dimensions: md.dimensions })
				)
			)
			.catch(() => {
				// Autocomplete just degrades to keyword-only; the editor itself is unaffected.
			});
	});

	let cubeIndex = $derived.by(() => {
		const map = new Map<string, SaikuCube>();
		for (const conn of datasources.connections) {
			if (conn.type === 'OSSIE') continue; // MDX only applies to OLAP cubes.
			for (const cat of conn.catalogs) {
				for (const sch of cat.schemas) {
					for (const cube of sch.cubes) {
						map.set(cubeKey(cube), cube);
					}
				}
			}
		}
		return map;
	});

	function onCubeChange(e: Event) {
		const key = (e.currentTarget as HTMLSelectElement).value;
		selectedCube = key ? (cubeIndex.get(key) ?? null) : null;
	}

	async function onRefreshCubes() {
		if (!session.current) return;
		await datasources.refresh(session.current.username);
	}

	async function onRun() {
		if (!selectedCube) {
			toasts.info(i18n.t('workbench.warning.selectCube', 'Select a cube first'));
			return;
		}
		if (!mdxBuffer.trim()) {
			toasts.info(i18n.t('workbench.warning.emptyMdx', 'Type an MDX query first'));
			return;
		}
		const q = newQuery(selectedCube);
		q.type = 'MDX';
		q.mdx = mdxBuffer;
		running = true;
		try {
			result = await executeQuery(q);
			mdxHistory.push({ mdx: mdxBuffer, cube: selectedCube, ranAt: new Date().toISOString() });
		} catch (e) {
			toasts.danger(
				i18n.t('workbench.run.failed', 'Query failed'),
				e instanceof Error ? e.message : String(e)
			);
		} finally {
			running = false;
		}
	}

	function loadHistoryEntry(entry: MdxHistoryEntry) {
		selectedCube = entry.cube;
		mdxBuffer = entry.mdx;
	}

	function defaultHomeFolder(): string {
		const u = session.current?.username;
		return u ? `homes/${u}` : 'homes';
	}

	async function ensureRepoLoaded() {
		if (!repository.loaded && !repository.loading) {
			await repository.refresh();
		}
	}

	async function onSaveAs() {
		if (!selectedCube) {
			toasts.info(i18n.t('workbench.warning.selectCube', 'Select a cube first'));
			return;
		}
		await ensureRepoLoaded();
		saveOpen = true;
	}

	async function onSavePick(folder: string, name: string) {
		saveOpen = false;
		if (!selectedCube) return;
		const filename = name.endsWith('.saiku') ? name : `${name}.saiku`;
		const path = folder ? `${folder}/${filename}` : filename;
		const q = newQuery(selectedCube);
		q.type = 'MDX';
		q.mdx = mdxBuffer;
		try {
			await saveResource(path, JSON.stringify(q));
			toasts.success(i18n.t('toast.saved'), path);
			await repository.refresh();
		} catch (e) {
			toasts.danger(i18n.t('toast.saveFailed'), e instanceof Error ? e.message : String(e));
		}
	}
</script>

<div class="mdx-workbench">
	<div class="mdx-workbench__toolbar">
		<label class="mdx-workbench__cube-label" for="workbench-cube-select"
			>{i18n.t('cubes.label')}</label
		>
		<select
			id="workbench-cube-select"
			class="rounded-sm border border-border-strong bg-bg px-3 py-2 text-sm text-fg"
			value={selectedCube ? cubeKey(selectedCube) : ''}
			onchange={onCubeChange}
			disabled={datasources.loading}
		>
			<option value="">
				{datasources.loading ? i18n.t('cubes.loading') : i18n.t('cubes.selectPrompt')}
			</option>
			{#each datasources.connections as conn (conn.name)}
				{#if conn.type !== 'OSSIE'}
					{#each conn.catalogs as cat (cat.name)}
						{#each cat.schemas as sch (sch.name)}
							{#if sch.cubes.length > 0}
								<optgroup label="{sch.name || cat.name}  ({conn.name})">
									{#each sch.cubes as cube (cubeKey(cube))}
										{#if cube.visible !== false}
											<option value={cubeKey(cube)}>{cube.caption || cube.name}</option>
										{/if}
									{/each}
								</optgroup>
							{/if}
						{/each}
					{/each}
				{/if}
			{/each}
		</select>
		<button
			type="button"
			class="icon-btn"
			onclick={onRefreshCubes}
			title={i18n.t('cubes.refresh')}
			aria-label={i18n.t('cubes.refresh')}
			disabled={datasources.loading}
		>
			<RotateCw size={14} class={datasources.loading ? 'spin' : ''} />
		</button>
		<div class="mdx-workbench__spacer"></div>
		<Button size="sm" onclick={onRun} disabled={running}>
			<Play size={14} />
			<span>{running ? i18n.t('workbench.running', 'Running…') : i18n.t('modal.mdx.run')}</span>
		</Button>
		<Button size="sm" variant="outline" onclick={onSaveAs}>
			<Save size={14} /> <span>{i18n.t('toolbar.saveAs', 'Save as…')}</span>
		</Button>
		<Button
			size="sm"
			variant={historyOpen ? 'secondary' : 'outline'}
			onclick={() => (historyOpen = !historyOpen)}
		>
			<History size={14} /> <span>{i18n.t('workbench.history.title', 'History')}</span>
		</Button>
	</div>

	<div class="mdx-workbench__body">
		<div class="mdx-workbench__main">
			<MonacoEditor
				value={mdxBuffer}
				language="mdx"
				minHeight="220px"
				onChange={(v) => (mdxBuffer = v)}
			/>
			<div class="mdx-workbench__results">
				<MdxResultGrid {result} />
			</div>
		</div>
		{#if historyOpen}
			<aside
				class="mdx-workbench__history"
				aria-label={i18n.t('workbench.history.title', 'History')}
			>
				<div class="mdx-workbench__history-head">
					<span>{i18n.t('workbench.history.title', 'History')}</span>
					{#if mdxHistory.all().length > 0}
						<button
							type="button"
							class="icon-btn"
							onclick={() => mdxHistory.clear()}
							title={i18n.t('workbench.history.clear', 'Clear history')}
							aria-label={i18n.t('workbench.history.clear', 'Clear history')}
						>
							<Trash2 size={14} />
						</button>
					{/if}
				</div>
				{#if mdxHistory.all().length === 0}
					<p class="mdx-workbench__history-empty text-fg-muted">
						{i18n.t('workbench.history.empty', 'Queries you run appear here.')}
					</p>
				{:else}
					<ul class="mdx-workbench__history-list">
						{#each mdxHistory.all() as entry (entry.ranAt)}
							<li>
								<button
									type="button"
									class="mdx-workbench__history-item"
									onclick={() => loadHistoryEntry(entry)}
								>
									<span class="mdx-workbench__history-cube"
										>{entry.cube.caption || entry.cube.name}</span
									>
									<span class="mdx-workbench__history-mdx">{entry.mdx}</span>
								</button>
							</li>
						{/each}
					</ul>
				{/if}
			</aside>
		{/if}
	</div>
</div>

{#if saveOpen}
	<SaveQueryModal
		defaultName="Untitled.saiku"
		defaultFolder={defaultHomeFolder()}
		folders={repository.folders}
		open={saveOpen}
		onSave={onSavePick}
		onCancel={() => (saveOpen = false)}
	/>
{/if}

<style>
	.mdx-workbench {
		display: flex;
		flex-direction: column;
		min-height: 0;
		flex: 1;
		gap: var(--space-3);
		padding: var(--space-4);
		overflow: hidden;
	}
	.mdx-workbench__toolbar {
		display: flex;
		align-items: center;
		gap: var(--space-2);
	}
	.mdx-workbench__cube-label {
		font-size: var(--fs-xs);
		font-weight: var(--weight-semibold);
		text-transform: uppercase;
		letter-spacing: 0.06em;
		color: hsl(var(--fg-muted));
	}
	.mdx-workbench__spacer {
		flex: 1;
	}
	.mdx-workbench__body {
		display: flex;
		min-height: 0;
		flex: 1;
		gap: var(--space-3);
		overflow: hidden;
	}
	.mdx-workbench__main {
		display: flex;
		flex-direction: column;
		min-width: 0;
		flex: 1;
		gap: var(--space-3);
		overflow: hidden;
	}
	.mdx-workbench__results {
		flex: 1;
		min-height: 0;
		overflow: auto;
	}
	.mdx-workbench__history {
		display: flex;
		flex-direction: column;
		width: 280px;
		flex-shrink: 0;
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		overflow: hidden;
	}
	.mdx-workbench__history-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: var(--space-2) var(--space-3);
		border-bottom: 1px solid hsl(var(--border));
		font-size: var(--fs-sm);
		font-weight: var(--weight-semibold);
		background: hsl(var(--bg-muted));
	}
	.mdx-workbench__history-empty {
		padding: var(--space-3);
		font-size: var(--fs-sm);
	}
	.mdx-workbench__history-list {
		list-style: none;
		margin: 0;
		padding: 0;
		overflow: auto;
	}
	.mdx-workbench__history-item {
		display: flex;
		flex-direction: column;
		width: 100%;
		gap: 2px;
		padding: var(--space-2) var(--space-3);
		border: 0;
		border-bottom: 1px solid hsl(var(--border));
		background: transparent;
		text-align: left;
		cursor: pointer;
	}
	.mdx-workbench__history-item:hover {
		background: hsl(var(--bg-subtle));
	}
	.mdx-workbench__history-cube {
		font-size: var(--fs-xs);
		font-weight: var(--weight-semibold);
		color: hsl(var(--fg-muted));
	}
	.mdx-workbench__history-mdx {
		font-size: var(--fs-xs);
		font-family: ui-monospace, monospace;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.icon-btn {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 32px;
		height: 32px;
		border: 1px solid hsl(var(--border-strong));
		border-radius: var(--radius-sm);
		background: hsl(var(--bg));
		color: hsl(var(--fg));
		cursor: pointer;
	}
	.icon-btn :global(.spin) {
		animation: mdx-workbench-spin 900ms linear infinite;
	}
	@keyframes mdx-workbench-spin {
		to {
			transform: rotate(360deg);
		}
	}
</style>

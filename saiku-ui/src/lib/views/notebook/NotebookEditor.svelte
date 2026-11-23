<script lang="ts">
	/*
	 * Notebook editor / viewer (issue #1108, phase 1: markdown + MDX cells,
	 * no chart cell yet). Deliberately local $state rather than a global
	 * store like dashboardStore — a notebook has no sibling toolbar/filter-
	 * bar components that need to subscribe to shared state from outside
	 * this tree, so a singleton store would only add indirection.
	 */
	import { untrack } from 'svelte';
	import {
		type Notebook,
		type NotebookCell,
		type NotebookCubeRef,
		loadNotebook,
		saveNotebook,
		newNotebook,
		newMarkdownCell,
		newMdxCell,
		normaliseNotebookPath,
		mintNotebookShare
	} from '$lib/api/notebooks';
	import { session } from '$lib/stores/session.svelte';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { Button, Input } from '$lib/components/ui';
	import { ArrowUp, ArrowDown, Trash2, Plus, Share2 } from '@lucide/svelte';
	import MarkdownCell from '$lib/views/notebook/MarkdownCell.svelte';
	import MdxCell from '$lib/views/notebook/MdxCell.svelte';

	interface Props {
		notebookPath: string;
		readOnly?: boolean;
	}

	let { notebookPath, readOnly = false }: Props = $props();

	let notebook = $state<Notebook | null>(null);
	let savedPath = $state<string | null>(null);
	let loading = $state(true);
	let saving = $state(false);
	let error = $state<string | null>(null);

	async function load(path: string) {
		loading = true;
		error = null;
		try {
			if (path === '') {
				notebook = newNotebook();
				savedPath = null;
			} else {
				notebook = await loadNotebook(path);
				savedPath = path;
			}
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	// Handles both the initial load (effects run once after first render,
	// same timing an onMount would give us) and a path change on an already-
	// mounted editor (SvelteKit reuses the component when only the [...path]
	// rest segment changes). untrack so re-running load() (which writes
	// savedPath) doesn't re-trigger this same effect.
	$effect(() => {
		const path = notebookPath;
		untrack(() => {
			if (path !== (savedPath ?? '')) void load(path);
		});
	});

	async function save() {
		if (!notebook || saving) return;
		saving = true;
		try {
			const username = session.current?.username ?? '';
			const target = savedPath ?? normaliseNotebookPath(`${notebook.name}.saikunb`, username);
			await saveNotebook(target, notebook);
			savedPath = target;
			toasts.success(i18n.t('toast.saved'), target);
		} catch (e) {
			toasts.danger(i18n.t('toast.saveFailed'), e instanceof Error ? e.message : String(e));
		} finally {
			saving = false;
		}
	}

	async function share() {
		if (!savedPath) {
			toasts.danger(i18n.t('notebook.share.saveFirst', 'Save the notebook before sharing it'));
			return;
		}
		try {
			const res = await mintNotebookShare(savedPath);
			await navigator.clipboard?.writeText(`${window.location.origin}${res.url}`);
			toasts.success(
				i18n.t('notebook.share.minted', 'Share link copied'),
				`${window.location.origin}${res.url}`
			);
		} catch (e) {
			toasts.danger(
				i18n.t('notebook.share.failed', 'Could not create share link'),
				e instanceof Error ? e.message : String(e)
			);
		}
	}

	function addCell(type: 'markdown' | 'mdx', afterIndex: number) {
		if (!notebook) return;
		const cell = type === 'markdown' ? newMarkdownCell() : newMdxCell();
		notebook.cells.splice(afterIndex + 1, 0, cell);
	}

	function removeCell(index: number) {
		if (!notebook) return;
		notebook.cells.splice(index, 1);
	}

	function moveCell(index: number, delta: number) {
		if (!notebook) return;
		const target = index + delta;
		if (target < 0 || target >= notebook.cells.length) return;
		const [cell] = notebook.cells.splice(index, 1);
		notebook.cells.splice(target, 0, cell);
	}

	function updateMarkdown(cell: NotebookCell, markdown: string) {
		cell.markdown = markdown;
	}

	function updateMdx(cell: NotebookCell, mdx: string, cube: NotebookCubeRef | undefined) {
		cell.mdx = mdx;
		cell.cube = cube;
	}
</script>

<div class="nb-editor">
	{#if loading}
		<div class="nb-editor__state">{i18n.t('notebook.loading', 'Loading notebook…')}</div>
	{:else if error}
		<div class="nb-editor__state nb-editor__state--error">{error}</div>
	{:else if notebook}
		{#if !readOnly}
			<header class="nb-editor__header">
				<Input
					bind:value={notebook.name}
					class="nb-editor__name"
					placeholder={i18n.t('notebook.namePlaceholder', 'Untitled notebook')}
				/>
				<div class="nb-editor__actions">
					<Button variant="outline" onclick={share}>
						<Share2 size={14} />
						{i18n.t('notebook.share.button', 'Share')}
					</Button>
					<Button onclick={save} disabled={saving}>
						{saving ? i18n.t('toast.saving', 'Saving…') : i18n.t('modal.save', 'Save')}
					</Button>
				</div>
			</header>
		{:else}
			<header class="nb-editor__header nb-editor__header--readonly">
				<span class="nb-editor__name-ro">{notebook.name}</span>
				<span class="nb-editor__badge"
					>{i18n.t('notebook.share.readOnlyBadge', 'Read-only shared view')}</span
				>
			</header>
		{/if}

		<div class="nb-editor__cells">
			{#each notebook.cells as cell, i (cell.id)}
				<div class="nb-editor__cell">
					{#if !readOnly}
						<div class="nb-editor__cell-rail">
							<button
								type="button"
								aria-label="Move up"
								disabled={i === 0}
								onclick={() => moveCell(i, -1)}
							>
								<ArrowUp size={12} />
							</button>
							<button
								type="button"
								aria-label="Move down"
								disabled={i === notebook.cells.length - 1}
								onclick={() => moveCell(i, 1)}
							>
								<ArrowDown size={12} />
							</button>
							<button type="button" aria-label="Delete cell" onclick={() => removeCell(i)}>
								<Trash2 size={12} />
							</button>
						</div>
					{/if}
					<div class="nb-editor__cell-body">
						{#if cell.type === 'markdown'}
							<MarkdownCell
								markdown={cell.markdown ?? ''}
								{readOnly}
								onChange={(v) => updateMarkdown(cell, v)}
							/>
						{:else}
							<MdxCell
								mdx={cell.mdx ?? ''}
								cube={cell.cube}
								{readOnly}
								onChange={(mdx, cube) => updateMdx(cell, mdx, cube)}
							/>
						{/if}
					</div>
				</div>
				{#if !readOnly}
					<div class="nb-editor__insert">
						<button type="button" onclick={() => addCell('markdown', i)}>
							<Plus size={12} />
							{i18n.t('notebook.cell.addMarkdown', 'Markdown')}
						</button>
						<button type="button" onclick={() => addCell('mdx', i)}>
							<Plus size={12} />
							{i18n.t('notebook.cell.addMdx', 'MDX')}
						</button>
					</div>
				{/if}
			{/each}
		</div>
	{/if}
</div>

<style>
	.nb-editor {
		flex: 1;
		min-height: 0;
		overflow: auto;
		max-width: 960px;
		margin: 0 auto;
		padding: 1rem 1.5rem 4rem;
		width: 100%;
		box-sizing: border-box;
	}
	.nb-editor__state {
		display: flex;
		align-items: center;
		justify-content: center;
		height: 200px;
		color: hsl(var(--fg-muted));
	}
	.nb-editor__state--error {
		color: hsl(var(--danger, var(--fg)));
	}
	.nb-editor__header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		padding-bottom: 0.75rem;
		border-bottom: 1px solid hsl(var(--border));
		margin-bottom: 0.5rem;
	}
	.nb-editor__actions {
		display: flex;
		gap: 8px;
		flex-shrink: 0;
	}
	:global(.nb-editor__name) {
		max-width: 420px;
		font-size: var(--fs-lg);
		font-weight: var(--weight-semibold);
	}
	.nb-editor__name-ro {
		font-size: var(--fs-xl);
		font-weight: var(--weight-bold);
	}
	.nb-editor__badge {
		font-size: var(--fs-sm);
		color: hsl(var(--fg-muted));
		padding: 2px var(--space-2);
		border: 1px solid hsl(var(--border));
		border-radius: 999px;
		background: hsl(var(--bg-subtle));
	}
	.nb-editor__cells {
		display: flex;
		flex-direction: column;
	}
	.nb-editor__cell {
		display: flex;
		gap: 6px;
		border: 1px solid transparent;
		border-radius: var(--radius-md, 6px);
	}
	.nb-editor__cell:hover {
		border-color: hsl(var(--border));
	}
	.nb-editor__cell-rail {
		display: flex;
		flex-direction: column;
		gap: 2px;
		padding-top: 0.5rem;
		opacity: 0.4;
	}
	.nb-editor__cell:hover .nb-editor__cell-rail {
		opacity: 1;
	}
	.nb-editor__cell-rail button {
		width: 20px;
		height: 20px;
		display: flex;
		align-items: center;
		justify-content: center;
		border: 1px solid hsl(var(--border));
		border-radius: 4px;
		background: hsl(var(--bg));
		color: hsl(var(--fg-muted));
		cursor: pointer;
	}
	.nb-editor__cell-rail button:disabled {
		opacity: 0.3;
		cursor: not-allowed;
	}
	.nb-editor__cell-body {
		flex: 1;
		min-width: 0;
	}
	.nb-editor__insert {
		display: flex;
		gap: 8px;
		justify-content: center;
		padding: 2px 0;
		opacity: 0;
	}
	.nb-editor__cells:hover .nb-editor__insert {
		opacity: 1;
	}
	.nb-editor__insert button {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-size: var(--fs-xs);
		padding: 2px 8px;
		border: 1px dashed hsl(var(--border));
		border-radius: 999px;
		background: hsl(var(--bg));
		color: hsl(var(--fg-muted));
		cursor: pointer;
	}
</style>

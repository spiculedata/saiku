<script lang="ts">
	/*
	 * Notebook catalogue (issue #1108) — create / list / open / delete.
	 * Deliberately a small subset of DashboardIndex's feature set (no
	 * folder tree, favourites, tags, ACL editor): phase 1 of #1108 asks for
	 * "New /ui/notebooks route with create / list / open", nothing more.
	 */
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { base } from '$app/paths';
	import { listRepository, flatten, type RepositoryNode } from '$lib/api/repository';
	import {
		saveNotebook,
		deleteNotebook,
		newNotebook,
		normaliseNotebookPath,
		displayPath
	} from '$lib/api/notebooks';
	import { session } from '$lib/stores/session.svelte';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { Button, Input } from '$lib/components/ui';
	import Modal from '$lib/components/Modal.svelte';
	import { FileText, Plus, Trash2 } from '@lucide/svelte';

	let notebooks = $state<RepositoryNode[]>([]);
	let loading = $state(true);
	let error = $state<string | null>(null);

	let newModalOpen = $state(false);
	let newName = $state('');
	let creating = $state(false);
	let createError = $state<string | null>(null);

	let deletingPath = $state<string | null>(null);

	async function refresh() {
		loading = true;
		error = null;
		try {
			const tree = await listRepository(['saikunb']);
			notebooks = flatten(tree).filter((n) => n.type === 'FILE');
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	onMount(() => void refresh());

	function openNewModal() {
		newName = '';
		createError = null;
		newModalOpen = true;
	}

	async function createNotebook() {
		if (!newName.trim()) {
			createError = i18n.t('notebook.new.nameRequired', 'Name is required');
			return;
		}
		creating = true;
		createError = null;
		try {
			const path = normaliseNotebookPath(
				`${newName.trim()}.saikunb`,
				session.current?.username ?? ''
			);
			const nb = newNotebook(newName.trim());
			await saveNotebook(path, nb);
			newModalOpen = false;
			await goto(`${base}/notebooks/${path}`);
		} catch (e) {
			createError = e instanceof Error ? e.message : String(e);
		} finally {
			creating = false;
		}
	}

	function open(path: string) {
		void goto(`${base}/notebooks/${path}`);
	}

	function handleDelete(e: MouseEvent, path: string) {
		e.stopPropagation();
		deletingPath = path;
	}

	async function confirmDelete() {
		const path = deletingPath;
		if (!path) return;
		deletingPath = null;
		try {
			await deleteNotebook(path);
			toasts.success(i18n.t('toast.deleted', 'Deleted'), path);
			await refresh();
		} catch (e) {
			toasts.danger(
				i18n.t('toast.deleteFailed', 'Delete failed'),
				e instanceof Error ? e.message : String(e)
			);
		}
	}
</script>

<div class="nb-index">
	<header class="nb-index__header">
		<h1>{i18n.t('topbar.notebooks', 'Notebooks')}</h1>
		<Button onclick={openNewModal}>
			<Plus size={14} />
			{i18n.t('notebook.new.button', 'New notebook')}
		</Button>
	</header>

	{#if loading}
		<div class="nb-index__state">{i18n.t('notebook.loading', 'Loading notebook…')}</div>
	{:else if error}
		<div class="nb-index__state nb-index__state--error">{error}</div>
	{:else if notebooks.length === 0}
		<div class="nb-index__state">
			{i18n.t('notebook.empty', 'No notebooks yet. Create one to get started.')}
		</div>
	{:else}
		<ul class="nb-index__list">
			{#each notebooks as node (node.path)}
				<li class="nb-index__row">
					<button type="button" class="nb-index__open" onclick={() => open(node.path)}>
						<FileText size={16} />
						<span class="nb-index__name">{displayPath(node.name)}</span>
						<span class="nb-index__path">{node.path}</span>
					</button>
					<button
						type="button"
						class="nb-index__delete"
						aria-label={i18n.t('modal.delete', 'Delete')}
						onclick={(e) => handleDelete(e, node.path)}
					>
						<Trash2 size={14} />
					</button>
				</li>
			{/each}
		</ul>
	{/if}
</div>

<Modal
	title={i18n.t('notebook.new.title', 'New notebook')}
	open={newModalOpen}
	onClose={() => (newModalOpen = false)}
>
	<div class="field__label">{i18n.t('notebook.new.nameLabel', 'Name')}</div>
	<Input
		bind:value={newName}
		placeholder={i18n.t('notebook.namePlaceholder', 'Untitled notebook')}
	/>
	{#if createError}
		<p class="nb-index__create-error">{createError}</p>
	{/if}
	{#snippet footer()}
		<Button variant="outline" onclick={() => (newModalOpen = false)}>{i18n.t('modal.close')}</Button
		>
		<Button onclick={createNotebook} disabled={creating}>
			{creating ? i18n.t('toast.saving', 'Saving…') : i18n.t('notebook.new.create', 'Create')}
		</Button>
	{/snippet}
</Modal>

<Modal
	title={i18n.t('modal.delete', 'Delete')}
	open={deletingPath !== null}
	onClose={() => (deletingPath = null)}
>
	<p>{i18n.t('notebook.delete.confirm', 'Delete this notebook? This cannot be undone.')}</p>
	<p class="nb-index__delete-path">{deletingPath}</p>
	{#snippet footer()}
		<Button variant="outline" onclick={() => (deletingPath = null)}>{i18n.t('modal.close')}</Button>
		<Button variant="destructive" onclick={confirmDelete}>{i18n.t('modal.delete', 'Delete')}</Button
		>
	{/snippet}
</Modal>

<style>
	.nb-index {
		flex: 1;
		min-height: 0;
		overflow: auto;
		max-width: 960px;
		margin: 0 auto;
		padding: 1.5rem;
		width: 100%;
		box-sizing: border-box;
	}
	.nb-index__header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 1rem;
	}
	.nb-index__header h1 {
		font-size: var(--fs-xl);
		font-weight: var(--weight-bold);
		margin: 0;
	}
	.nb-index__state {
		color: hsl(var(--fg-muted));
		padding: 2rem 0;
		text-align: center;
	}
	.nb-index__state--error {
		color: hsl(var(--danger, var(--fg)));
	}
	.nb-index__list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.nb-index__row {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 2px 6px 2px 2px;
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-md, 6px);
	}
	.nb-index__row:hover {
		background: hsl(var(--bg-subtle));
	}
	.nb-index__open {
		display: flex;
		align-items: center;
		gap: 10px;
		flex: 1;
		min-width: 0;
		padding: 6px 8px;
		background: transparent;
		border: none;
		color: hsl(var(--fg));
		text-align: left;
		cursor: pointer;
		font: inherit;
	}
	.nb-index__name {
		font-weight: var(--weight-semibold);
	}
	.nb-index__path {
		color: hsl(var(--fg-muted));
		font-size: var(--fs-sm);
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.nb-index__delete {
		background: transparent;
		border: none;
		color: hsl(var(--fg-muted));
		cursor: pointer;
		padding: 4px;
		display: flex;
	}
	.nb-index__delete:hover {
		color: hsl(var(--danger, var(--fg)));
	}
	.nb-index__create-error {
		color: hsl(var(--danger, var(--fg)));
		font-size: var(--fs-sm);
	}
	.nb-index__delete-path {
		color: hsl(var(--fg-muted));
		font-size: var(--fs-sm);
	}
</style>

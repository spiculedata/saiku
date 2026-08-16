<script lang="ts">
	import { untrack } from 'svelte';
	import { Button } from '$lib/components/ui';
	import { Folder, FolderPlus, FileText, ChevronRight, Home } from '@lucide/svelte';
	import { flatten, saveResource, type RepositoryNode } from '$lib/api/repository';
	import { repository } from '$lib/stores/repository.svelte';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';

	/*
	 * Single-pane file-explorer for the saiku repository.
	 *
	 * Designed to drop into Save and Open modals so both use the same
	 * folder-browsing affordance instead of the prior dropdown
	 * (Save) / flat search (Open). Navigation is breadcrumb-based: the
	 * current folder is shown at the top, click a folder row to descend,
	 * click a breadcrumb segment to go back. Power users keep the search
	 * box for jump-to-file when they know the name.
	 *
	 * Two modes:
	 *   - "load": double-click (or click + Enter) a file to select.
	 *     selectedPath is updated on single-click.
	 *   - "save": clicks select the target FOLDER (not file). Caller
	 *     supplies the filename via a separate input. New folder button
	 *     creates an empty folder at the current path.
	 *
	 * File type filter (e.g. ["saiku"]) trims the visible list. Folders
	 * always show.
	 */

	interface Props {
		mode: 'load' | 'save';
		/** File-type filter — only files matching these types are shown.
		 *  Folders are always visible. */
		fileTypes?: string[];
		/** Path currently selected. For load = the file. For save = the
		 *  target folder. */
		selectedPath: string;
		onSelect: (path: string) => void;
		/** Called when a load-mode file is "opened" (double-click / Enter). */
		onOpen?: (path: string) => void;
	}

	let { mode, fileTypes = ['saiku'], selectedPath, onSelect, onOpen }: Props = $props();

	// Browser state — current folder being viewed + search filter.
	let currentPath = $state<string>(untrack(() => deriveStartPath(selectedPath)));
	let search = $state<string>('');
	let newFolderName = $state<string>('');
	let creatingFolder = $state<boolean>(false);

	function deriveStartPath(initial: string): string {
		if (!initial) return '';
		if (initial.endsWith('/')) return initial.replace(/\/+$/, '');
		const idx = initial.lastIndexOf('/');
		return idx > 0 ? initial.slice(0, idx) : '';
	}

	$effect(() => {
		if (!repository.loaded && !repository.loading) {
			void repository.refresh();
		}
	});

	// All nodes (flat) — search filter matches the full path.
	const allNodes = $derived(flatten(repository.tree));

	// Folder tree lookup keyed by path. Each node's repoObjects holds
	// direct children, so we walk to currentPath once and list its
	// immediate children.
	const currentChildren = $derived<RepositoryNode[]>(listChildrenAt(repository.tree, currentPath));

	function listChildrenAt(tree: RepositoryNode[], path: string): RepositoryNode[] {
		if (!path) return tree;
		const segments = path.split('/').filter(Boolean);
		let cursor: RepositoryNode[] | undefined = tree;
		for (const seg of segments) {
			const next: RepositoryNode | undefined = cursor?.find(
				(n) => n.type === 'FOLDER' && n.name === seg
			);
			if (!next) return [];
			cursor = next.repoObjects ?? [];
		}
		return cursor ?? [];
	}

	// Search-filtered view across the whole tree (load mode). When
	// search is non-empty, ignore currentPath and show matching files
	// anywhere in the repo.
	const filtered = $derived.by(() => {
		const q = search.trim().toLowerCase();
		if (!q) return null;
		return allNodes
			.filter((n) => {
				if (n.type !== 'FILE') return false;
				if (!matchesFileType(n)) return false;
				return n.name.toLowerCase().includes(q) || n.path.toLowerCase().includes(q);
			})
			.slice(0, 100);
	});

	function matchesFileType(n: RepositoryNode): boolean {
		if (!fileTypes.length) return true;
		if (n.fileType && fileTypes.includes(n.fileType)) return true;
		return fileTypes.some((t) => n.path.endsWith(`.${t}`));
	}

	// Visible rows in current folder = subfolders first, then matching
	// files. Sorted alphabetically within each group.
	const visibleRows = $derived.by(() => {
		if (filtered) return filtered;
		const folders = currentChildren
			.filter((n) => n.type === 'FOLDER')
			.sort((a, b) => a.name.localeCompare(b.name));
		const files = currentChildren
			.filter((n) => n.type === 'FILE' && matchesFileType(n))
			.sort((a, b) => a.name.localeCompare(b.name));
		return [...folders, ...files];
	});

	const breadcrumbs = $derived(buildBreadcrumbs(currentPath));

	function buildBreadcrumbs(path: string): { label: string; path: string }[] {
		const out: { label: string; path: string }[] = [{ label: i18n.t('repo.root'), path: '' }];
		const segments = path.split('/').filter(Boolean);
		let acc = '';
		for (const seg of segments) {
			acc = acc ? `${acc}/${seg}` : seg;
			out.push({ label: seg, path: acc });
		}
		return out;
	}

	function onRowClick(node: RepositoryNode) {
		if (node.type === 'FOLDER') {
			currentPath = node.path.replace(/^\/+|\/+$/g, '');
			if (mode === 'save') onSelect(currentPath);
			return;
		}
		// File click in load mode selects; save mode ignores files.
		if (mode === 'load') onSelect(node.path);
	}

	function onRowDoubleClick(node: RepositoryNode) {
		if (node.type === 'FILE' && mode === 'load' && onOpen) {
			onOpen(node.path);
		}
	}

	function onRowKey(e: KeyboardEvent, node: RepositoryNode) {
		if (e.key === 'Enter') {
			e.preventDefault();
			if (node.type === 'FOLDER') onRowClick(node);
			else if (onOpen && mode === 'load') onOpen(node.path);
		}
	}

	async function createFolder() {
		const trimmed = newFolderName.trim().replace(/^\/+|\/+$/g, '');
		if (!trimmed) return;
		const target = currentPath ? `${currentPath}/${trimmed}` : trimmed;
		creatingFolder = true;
		try {
			// The save endpoint mkdirs parent dirs at write time, but it
			// requires a file. Drop a placeholder .saiku-keep marker so the
			// folder materialises. Backend ACL inheritance applies normally.
			await saveResource(`${target}/.saiku-keep`, '');
			await repository.refresh();
			currentPath = target;
			if (mode === 'save') onSelect(currentPath);
			newFolderName = '';
			toasts.success(i18n.t('repo.folderCreated'), target);
		} catch (e) {
			toasts.danger(i18n.t('repo.folderCreateFailed'), e instanceof Error ? e.message : String(e));
		} finally {
			creatingFolder = false;
		}
	}
</script>

<div class="repo-browser">
	<nav
		class="flex flex-wrap items-center gap-0.5 rounded-sm border border-border bg-bg-subtle px-3 py-2 text-sm"
		aria-label={i18n.t('repo.breadcrumb')}
	>
		{#each breadcrumbs as crumb, i (crumb.path)}
			{#if i > 0}<ChevronRight size={12} class="repo-browser__crumb-sep" />{/if}
			<button
				type="button"
				class="repo-browser__crumb"
				class:is-current={i === breadcrumbs.length - 1}
				onclick={() => (currentPath = crumb.path)}
			>
				{#if i === 0}<Home size={12} />{/if}
				{crumb.label}
			</button>
		{/each}
	</nav>

	<div class="repo-browser__search">
		<input
			type="text"
			class="w-full rounded-sm border border-border-strong bg-bg px-3 py-1.5 text-sm text-fg"
			placeholder={i18n.t('repo.searchPlaceholder')}
			bind:value={search}
		/>
	</div>

	{#if repository.loading}
		<p class="m-0 rounded-sm border border-border bg-bg p-6 text-center text-sm text-fg-subtle">
			{i18n.t('modal.open.loading')}
		</p>
	{:else if visibleRows.length === 0}
		<p class="m-0 rounded-sm border border-border bg-bg p-6 text-center text-sm text-fg-subtle">
			{search ? i18n.t('saved.noMatches') : i18n.t('repo.folderEmpty')}
		</p>
	{:else}
		<ul class="repo-browser__list">
			{#each visibleRows as node (node.path)}
				<li>
					<button
						type="button"
						class="repo-browser__row"
						class:is-selected={node.path === selectedPath}
						onclick={() => onRowClick(node)}
						ondblclick={() => onRowDoubleClick(node)}
						onkeydown={(e) => onRowKey(e, node)}
					>
						<span class="inline-flex text-fg-muted">
							{#if node.type === 'FOLDER'}
								<Folder size={14} />
							{:else}
								<FileText size={14} />
							{/if}
						</span>
						<span class="min-w-0 flex-1 overflow-hidden text-ellipsis whitespace-nowrap"
							>{node.name}</span
						>
						{#if filtered && node.type === 'FILE'}
							<span class="text-xs text-fg-subtle">{node.path}</span>
						{/if}
					</button>
				</li>
			{/each}
		</ul>
	{/if}

	{#if mode === 'save'}
		<div class="repo-browser__new-folder">
			<FolderPlus size={14} />
			<input
				type="text"
				class="repo-browser__new-folder-input"
				placeholder={i18n.t('repo.newFolderPlaceholder')}
				bind:value={newFolderName}
				onkeydown={(e) => {
					if (e.key === 'Enter') {
						e.preventDefault();
						void createFolder();
					}
				}}
			/>
			<Button
				variant="outline"
				disabled={!newFolderName.trim() || creatingFolder}
				onclick={() => void createFolder()}>{i18n.t('repo.createFolder')}</Button
			>
		</div>
	{/if}
</div>

<style>
	.repo-browser {
		display: flex;
		flex-direction: column;
		gap: var(--space-3);
		min-height: 360px;
	}
	.repo-browser__crumb {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		padding: 2px 6px;
		background: transparent;
		border: 0;
		border-radius: 3px;
		color: hsl(var(--fg-muted));
		cursor: pointer;
		font: inherit;
	}
	.repo-browser__crumb:hover {
		background: hsl(var(--bg-hover));
		color: hsl(var(--fg));
	}
	.repo-browser__crumb.is-current {
		color: hsl(var(--fg));
		font-weight: var(--weight-medium);
	}
	/* Applied via class={} on a lucide icon — needs :global so the
     scoped-style hash doesn't suppress it on the child component's root. */
	:global(.repo-browser__crumb-sep) {
		color: hsl(var(--fg-subtle));
	}
	.repo-browser__list {
		flex: 1;
		list-style: none;
		margin: 0;
		padding: 0;
		max-height: 40vh;
		overflow-y: auto;
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		background: hsl(var(--bg));
	}
	.repo-browser__row {
		display: flex;
		align-items: center;
		gap: var(--space-2);
		width: 100%;
		padding: var(--space-2) var(--space-3);
		background: transparent;
		border: 0;
		border-bottom: 1px solid hsl(var(--border));
		color: hsl(var(--fg));
		cursor: pointer;
		font: inherit;
		font-size: var(--fs-sm);
		text-align: left;
	}
	.repo-browser__row:last-child {
		border-bottom: 0;
	}
	.repo-browser__row:hover {
		background: hsl(var(--bg-hover));
	}
	.repo-browser__row.is-selected {
		background: hsl(var(--accent));
		color: hsl(var(--primary-strong));
	}
	.repo-browser__new-folder {
		display: flex;
		align-items: center;
		gap: var(--space-2);
		padding: var(--space-2);
		background: hsl(var(--bg-muted));
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		color: hsl(var(--fg-muted));
	}
	.repo-browser__new-folder-input {
		flex: 1;
		padding: 6px var(--space-2);
		background: hsl(var(--bg));
		color: hsl(var(--fg));
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		font: inherit;
		font-size: var(--fs-sm);
	}
</style>

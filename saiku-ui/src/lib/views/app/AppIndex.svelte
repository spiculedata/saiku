<script lang="ts">
	/*
	 * Apps landing page — the App Builder analogue of DashboardIndex.
	 *
	 * Lists every .saikuapp file in the repository (via listApps()), surfaces
	 * "Open" / "New app" / "New app from dashboard…" / "Delete" actions, and is
	 * the entry point from the topbar. Mirrors the dashboard catalogue's flow:
	 * create seeds a fresh doc through the appDoc store, saves it via
	 * AppResource, then navigates to /apps/<path> which renders AppShell.
	 *
	 * The import-from-dashboard path reuses RepositoryBrowser (load mode,
	 * filtered to .saikudash) as the dashboard picker, loads the chosen
	 * dashboard, and wraps its layout as the new app's first page.
	 */
	import { onMount } from 'svelte';
	import { Button } from '$lib/components/ui';
	import { goto } from '$app/navigation';
	import { base } from '$app/paths';
	import { AppWindow, LayoutDashboard } from '@lucide/svelte';
	import { listApps, deleteApp, type AppSummary } from '$lib/api/apps';
	import { loadDashboard, toRepoRelative } from '$lib/api/dashboards';
	import { appDoc } from '$lib/stores/appDoc.svelte';
	import { session } from '$lib/stores/session.svelte';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { importArgsFromDashboard } from '$lib/views/app/appImport';
	import { Skeleton } from '$lib/design-system';
	import EmptyState from '$lib/components/EmptyState.svelte';
	import Modal from '$lib/components/Modal.svelte';
	import RepositoryBrowser from '$lib/components/RepositoryBrowser.svelte';
	import NewAppModal from '$lib/modals/NewAppModal.svelte';
	import ConfirmModal from '$lib/modals/ConfirmModal.svelte';

	let entries = $state<AppSummary[]>([]);
	let loading = $state<boolean>(true);
	let loadError = $state<string | null>(null);
	let creating = $state<boolean>(false);
	let createError = $state<string | null>(null);

	let newModalOpen = $state<boolean>(false);
	let newModalName = $state<string>('Untitled app');
	let deletingPath = $state<string | null>(null);

	/** Dashboard picker (import) state. */
	let pickerOpen = $state<boolean>(false);
	let pickerSelected = $state<string>('');
	/** Layout captured from the chosen dashboard, pending the name/folder step.
	 *  Non-null marks the NewAppModal as an import (badge + newAppFromDashboard). */
	let pendingLayout = $state<unknown | null>(null);

	async function refresh(): Promise<void> {
		loading = true;
		loadError = null;
		try {
			entries = await listApps();
		} catch (e: unknown) {
			loadError = e instanceof Error ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	onMount(() => {
		void refresh();
	});

	function defaultHomePath(): string {
		const u = session.current?.username;
		return u ? `homes/${u}` : 'homes';
	}

	function basename(path: string): string {
		const p = path.split('/').pop() ?? path;
		return p.endsWith('.saikuapp') ? p.slice(0, -'.saikuapp'.length) : p;
	}

	// ------------------------------------------------------------------
	// New app (blank)
	// ------------------------------------------------------------------

	function handleNew(): void {
		createError = null;
		pendingLayout = null;
		newModalName = 'Untitled app';
		newModalOpen = true;
	}

	// ------------------------------------------------------------------
	// New app from dashboard — pick a dashboard, then name/folder it.
	// ------------------------------------------------------------------

	function handleImport(): void {
		createError = null;
		pickerSelected = '';
		pickerOpen = true;
	}

	async function onPickDashboard(): Promise<void> {
		const picked = pickerSelected;
		if (!picked) return;
		pickerOpen = false;
		creating = true;
		createError = null;
		try {
			const relPath = toRepoRelative(picked);
			const dashboard = await loadDashboard(relPath);
			const args = importArgsFromDashboard(dashboard, relPath);
			pendingLayout = args.layout;
			newModalName = args.name;
			newModalOpen = true;
		} catch (e: unknown) {
			createError = e instanceof Error ? e.message : String(e);
		} finally {
			creating = false;
		}
	}

	// ------------------------------------------------------------------
	// Create (shared by blank + import) — seed the doc, save, navigate.
	// ------------------------------------------------------------------

	async function onCreate(path: string, name: string): Promise<void> {
		newModalOpen = false;
		creating = true;
		createError = null;
		try {
			if (!path.endsWith('.saikuapp')) {
				createError = 'Path must end with .saikuapp.';
				return;
			}
			if (pendingLayout !== null) {
				appDoc.newAppFromDashboard(name, pendingLayout);
			} else {
				appDoc.newApp(name);
			}
			await appDoc.saveApp(path, name);
			await goto(`${base}/apps/${path}`);
		} catch (e: unknown) {
			createError = e instanceof Error ? e.message : String(e);
		} finally {
			creating = false;
			pendingLayout = null;
		}
	}

	// ------------------------------------------------------------------
	// Delete
	// ------------------------------------------------------------------

	function handleDelete(path: string): void {
		deletingPath = path;
	}

	async function confirmDelete(): Promise<void> {
		const path = deletingPath;
		if (!path) return;
		deletingPath = null;
		try {
			await deleteApp(path);
			await refresh();
			toasts.success(i18n.t('toast.deleted'), path);
		} catch (e: unknown) {
			toasts.danger(i18n.t('toast.deleteFailed'), e instanceof Error ? e.message : String(e));
		}
	}
</script>

<div class="box-border flex h-full min-w-0 flex-1 flex-col gap-4 overflow-y-auto p-6">
	<header class="head">
		<h1>Apps</h1>
		<div class="flex items-center gap-2">
			<Button variant="outline" onclick={handleImport} disabled={creating}>
				<LayoutDashboard size={14} /><span>New app from dashboard…</span>
			</Button>
			<Button onclick={handleNew} disabled={creating}>
				{creating ? 'Creating…' : '+ New app'}
			</Button>
		</div>
	</header>

	{#if createError}
		<div class="error">{createError}</div>
	{/if}

	{#if loading}
		<Skeleton rows={4} variant="list" />
	{:else if loadError}
		<div class="error">{loadError}</div>
	{:else if entries.length === 0}
		<EmptyState
			icon={AppWindow}
			title="No apps yet"
			description="Build a branded, multi-page app — or import an existing dashboard as a starting point."
			action={{ label: '+ New app', onClick: handleNew }}
		/>
	{:else}
		<ul class="list">
			{#each entries as e (e.path)}
				{@const relPath = toRepoRelative(e.path)}
				<li class="row">
					<a class="link" href="{base}/apps/{relPath}" title={relPath}>
						<span class="font-medium">{e.name ? basename(e.name) : basename(relPath)}</span>
						<span class="overflow-hidden text-xs text-ellipsis whitespace-nowrap text-fg-muted"
							>{relPath}</span
						>
					</a>
					<Button variant="destructive" onclick={() => handleDelete(relPath)} title="Delete">
						Delete
					</Button>
				</li>
			{/each}
		</ul>
	{/if}
</div>

<NewAppModal
	defaultName={newModalName}
	defaultFolder={defaultHomePath()}
	open={newModalOpen}
	importing={pendingLayout !== null}
	onCreate={(p, n) => void onCreate(p, n)}
	onCancel={() => {
		newModalOpen = false;
		pendingLayout = null;
	}}
/>

<Modal
	title="Choose a dashboard to import"
	open={pickerOpen}
	size="lg"
	onClose={() => (pickerOpen = false)}
>
	<RepositoryBrowser
		mode="load"
		fileTypes={['saikudash']}
		selectedPath={pickerSelected}
		onSelect={(p) => (pickerSelected = p)}
		onOpen={(p) => {
			pickerSelected = p;
			void onPickDashboard();
		}}
	/>
	{#snippet footer()}
		<Button variant="outline" onclick={() => (pickerOpen = false)}>{i18n.t('modal.cancel')}</Button>
		<Button onclick={() => void onPickDashboard()} disabled={!pickerSelected}>Choose</Button>
	{/snippet}
</Modal>

<ConfirmModal
	title="Delete app"
	message={deletingPath ? `Delete "${deletingPath}"? This cannot be undone.` : ''}
	confirmLabel="Delete"
	variant="danger"
	open={deletingPath !== null}
	onConfirm={confirmDelete}
	onCancel={() => (deletingPath = null)}
/>

<style>
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
	}
	.head h1 {
		margin: 0;
		font-size: 1.25rem;
	}
	.error {
		padding: 0.5rem 0.75rem;
		background: color-mix(in srgb, hsl(var(--danger)) 14%, transparent);
		color: hsl(var(--danger));
		border-radius: 4px;
		font-size: 0.875rem;
	}
	.list {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 0.375rem;
	}
	.row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.5rem 0.75rem;
		border: 1px solid hsl(var(--border));
		border-radius: 6px;
		background: hsl(var(--bg));
	}
	.row:hover {
		background: hsl(var(--bg-subtle));
	}
	.link {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 0.125rem;
		text-decoration: none;
		color: inherit;
		min-width: 0;
	}
</style>

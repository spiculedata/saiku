<script lang="ts">
	/*
	 * Top-level App Builder editor / viewer.
	 *
	 * Route-level lifecycle wrapper around AppShell — the app analogue of
	 * DashboardEditor. Loads the .saikuapp via the appDoc store on mount and
	 * whenever the path changes (SvelteKit reuses the route component across
	 * navigations), then renders AppShell against appDoc.current.
	 *
	 * Edit vs. view: a saved app opens in read-only "view"; a newly created one
	 * opens in edit (appOpenMode). A toggle in the header controls flips between
	 * them — AppShell's `editable` prop drives whether page add / rename
	 * affordances and in-grid editing are shown.
	 */
	import { page } from '$app/state';
	import { appDoc } from '$lib/stores/appDoc.svelte';
	import { PAGE_PARAM } from '$lib/dashboard/urlFilterState';
	import { Button } from '$lib/components/ui';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { Save, Pencil, Eye, Palette } from '@lucide/svelte';
	import AppShell from '$lib/views/app/AppShell.svelte';
	import { initialAppMode, type AppMode } from '$lib/views/app/appOpenMode';
	import { createAppLoader } from '$lib/views/app/appLoader';
	import AppInspector, {
		type InspectorSection
	} from '$lib/views/app/inspector/AppInspector.svelte';

	interface Props {
		appPath: string;
	}

	let { appPath }: Props = $props();

	/** View mode. A saved app opens in read-only "view" (the clean published
	 *  experience — no add-tile / filters / tile toolbars); the header's Edit
	 *  button toggles into "edit". A brand-new app opens in edit — the create
	 *  flow navigates with `?edit=1` (see appOpenMode). Read once at init: the
	 *  app's URL-state mirror rewrites the query string afterwards. */
	let mode = $state<AppMode>(initialAppMode(page.url.searchParams));
	let saving = $state<boolean>(false);

	// Kiosk: `?chrome=none` renders the app as a pure viewer — no edit/save
	// controls, view mode forced. Latched (the app's URL-state mirror rewrites the
	// query string), same as the layout's chrome-hide.
	let kiosk = $state(false);
	$effect(() => {
		if (page.url.searchParams.get('chrome') === 'none') {
			kiosk = true;
			mode = 'view';
		}
	});

	// Load the app on mount AND whenever the path changes — one trigger, not
	// two. This used to be an `onMount` load plus a path-watching effect, and
	// both fired on first mount: the app was fetched twice and, worse, the second
	// response landed AFTER the deep-link restore had already run, resetting the
	// app to page 0 and letting the URL mirror rewrite the shared link
	// (saiku#1766). `createAppLoader` holds the one-load-per-path rule; this
	// effect only feeds it the current path plus the `?p=` deep link, and runs
	// at least once — so it covers the initial load too.
	const requestAppLoad = createAppLoader(({ path, pageId }) => {
		void appDoc.loadApp(path, { pageId });
	});
	$effect(() => {
		const p = appPath;
		if (!p) return;
		// `?p=` is read per load rather than latched at init: a client-side
		// navigation to a different app brings its own page id, while a re-load
		// re-applies whichever page the URL names right now. The store validates
		// the id, so a stale link can't strand the app on no page.
		requestAppLoad(p, page.url.searchParams.get(PAGE_PARAM));
	});

	async function handleSave(): Promise<void> {
		const app = appDoc.current;
		const path = appDoc.savedPath;
		if (!app || !path) return;
		saving = true;
		try {
			await appDoc.saveApp(path, app.name);
			toasts.success('Saved', app.name);
		} catch (e: unknown) {
			toasts.danger('Save failed', e instanceof Error ? e.message : String(e));
		} finally {
			saving = false;
		}
	}

	function toggleMode(): void {
		mode = mode === 'edit' ? 'view' : 'edit';
		if (mode !== 'edit') inspectorOpen = false;
	}

	/** App Inspector open state + active section (edit mode only). */
	let inspectorOpen = $state(false);
	let inspectorSection = $state<InspectorSection>('theme');

	/** Selection model: a click on a live chrome element (in edit mode) opens the
	 *  inspector on the matching section. */
	function openInspector(sectionId: InspectorSection): void {
		inspectorSection = sectionId;
		inspectorOpen = true;
	}
</script>

{#if appDoc.loading}
	<div class="app-editor__state">{i18n.t('modal.open.loading')}</div>
{:else if appDoc.error}
	<div class="app-editor__state text-danger">{appDoc.error}</div>
{:else if appDoc.current}
	<AppShell
		app={appDoc.current}
		editable={mode === 'edit'}
		onEditChrome={mode === 'edit' ? openInspector : undefined}
	>
		{#snippet controls()}
			{#if !kiosk}
				<Button
					variant="outline"
					size="sm"
					onclick={toggleMode}
					title={mode === 'edit' ? 'Switch to view mode' : 'Switch to edit mode'}
				>
					{#if mode === 'edit'}
						<Eye size={14} /><span>View</span>
					{:else}
						<Pencil size={14} /><span>Edit</span>
					{/if}
				</Button>
				{#if mode === 'edit'}
					<Button
						variant={inspectorOpen ? 'default' : 'outline'}
						size="sm"
						onclick={() => (inspectorOpen = !inspectorOpen)}
						title="Design — brand, header, nav, assistant, pages"
					>
						<Palette size={14} /><span>Design</span>
					</Button>
					<Button size="sm" onclick={() => void handleSave()} disabled={saving}>
						<Save size={14} /><span>{saving ? 'Saving…' : 'Save'}</span>
					</Button>
				{/if}
			{/if}
		{/snippet}
	</AppShell>
	{#if inspectorOpen && mode === 'edit'}
		<div class="app-editor__inspector">
			<AppInspector initialSection={inspectorSection} onClose={() => (inspectorOpen = false)} />
		</div>
	{/if}
{:else}
	<div class="app-editor__state">No app loaded.</div>
{/if}

<style>
	.app-editor__state {
		flex: 1;
		display: flex;
		align-items: center;
		justify-content: center;
		color: hsl(var(--fg-muted));
		font-size: var(--fs-md);
	}
	/* Brand & Theme inspector — a right-edge overlay so it floats above the app
     (including the assistant column) while editing, without reflowing it. */
	.app-editor__inspector {
		position: fixed;
		top: 0;
		right: 0;
		bottom: 0;
		z-index: 50;
		display: flex;
		box-shadow: -8px 0 28px rgba(0, 0, 0, 0.18);
	}
</style>

<script lang="ts">
	import { onMount } from 'svelte';
	import { Button } from '$lib/components/ui';
	import type { SaikuSession } from '$lib/api/session';
	import Modal from '$lib/components/Modal.svelte';
	import { ContextMenu } from '$lib/design-system';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import CubePicker from '$lib/views/CubePicker.svelte';
	import DimensionList from '$lib/views/DimensionList.svelte';
	import OssieSchemaTree from '$lib/views/OssieSchemaTree.svelte';
	import OssieQueryCanvas from '$lib/views/OssieQueryCanvas.svelte';
	import WorkspaceToolbar from '$lib/views/WorkspaceToolbar.svelte';
	import QueryCanvas from '$lib/views/QueryCanvas.svelte';
	import AiQueryDrawer from '$lib/views/AiQueryDrawer.svelte';
	import AiDashboardBuildingOverlay from '$lib/views/AiDashboardBuildingOverlay.svelte';
	import PrefsMenu from '$lib/components/PrefsMenu.svelte';
	import { query } from '$lib/stores/query.svelte';
	import { selection } from '$lib/stores/selection.svelte';
	import { tabs } from '$lib/stores/tabs.svelte';
	import { embed } from '$lib/stores/embed.svelte';
	import { aiAskHealth } from '$lib/stores/aiAskHealth.svelte';
	import { mailHealth } from '$lib/stores/mailHealth.svelte';
	import { deserializeQueryFromHash, serializeQueryToHash, type ThinQuery } from '$lib/api/query';
	import { datasources } from '$lib/stores/datasources.svelte';
	import { platform } from '$lib/stores/platform.svelte';
	import {
		findCubeByRef,
		parseStarterCubeRef,
		pickStarterMeasureAndLevel
	} from '$lib/api/starterCube';
	import type { ChartType, ChartOptions } from '$lib/views/chartTypes';
	import { DEFAULT_CHART_OPTIONS } from '$lib/views/chartTypes';

	interface Props {
		session: SaikuSession;
	}

	let { session }: Props = $props();
	let aboutOpen = $state(false);
	let aiDrawerOpen = $state(false);

	// Mirror of ContextMenu.svelte's item shape (the component exports it from
	// its instance script, which isn't importable as a type — QueryCanvas
	// declares the same local alias).
	type ContextMenuItem = {
		id: string;
		label: string;
		disabled?: boolean;
		danger?: boolean;
		sep?: boolean;
	};

	/** Hand the model's MDX to the active query tab and re-run.
	 *
	 *  Saiku#1131: clear `query.current.name` first. ThinQueryService
	 *  treats the queryName as a stable id for its session-scoped context
	 *  cache (`ThinQueryService.createQuery()` line 224 — only stores the
	 *  inbound ThinQuery if the name isn't already in `context`). When a
	 *  cube was just `initFor`-ed and then we switch to MDX-mode on the
	 *  same tab, the cached entry for that UUID still points at the prior
	 *  ThinQuery (empty queryModel, no mdx). `ArrowCellsetWriter` then
	 *  reads `tqAfter.getMdx()` off that stale entry and the response
	 *  carries the wrong mdx + 0 rows even though the wire request was
	 *  correct.
	 *
	 *  Clearing the name forces `createQuery` to mint a fresh UUID and a
	 *  fresh `QueryContext`, so the rest of the path reads our MDX. The
	 *  store's `query.run()` repopulates `current.name` from the response.
	 *
	 *  Closes the drawer so the user sees their cellset; the canvas
	 *  re-renders with the result and existing grid / chart / drill /
	 *  export features keep working. */
	/**
	 * Push the AI's result into the active workspace tab and run it.
	 *
	 * Prefer the structured queryModel (chip-editable in the builder) over
	 * the MDX string (opaque to the chip UI — user can't drag/drop). Server
	 * returns both whenever the AI's request converted successfully; only
	 * degraded / VALIDATION_ERROR paths arrive MDX-only.
	 *
	 * Sets queryModel + type=QUERYMODEL so QueryCanvas renders the chip
	 * surface; clearing query.current.mdx removes any stale MDX from prior
	 * MDX-mode iterations on the same tab. The drawer stays open so the
	 * user can keep iterating — every AI response auto-runs in the canvas
	 * behind the drawer, and the conversation thread persists.
	 */
	/**
	 * Apply an AI-emitted view change to the workspace. The drawer's emit_view_change tool returns
	 * a target viewMode + chartType — we just assign them to the query store. No re-execution needed
	 * because the cellset doesn't change; the chart layer re-renders on `query.viewMode` /
	 * `query.chartType` changes. Drawer stays open so the user can iterate.
	 */
	function handleApplyViewChange(vc: { viewMode: 'grid' | 'chart'; chartType?: string }): void {
		if (!query.current) return;
		// Capture the current view state so the user can undo the AI's choice
		// ("Switched to chart" → Cmd-Z → back to grid).
		query.captureForUndo();
		query.viewMode = vc.viewMode;
		if (vc.viewMode === 'chart' && vc.chartType) {
			query.chartType = vc.chartType as typeof query.chartType;
		}
	}

	async function handleEditInCanvas(payload: { mdx: string; queryModel?: unknown }): Promise<void> {
		if (!query.current) return;
		// Capture the user's current query before the AI replaces it — Cmd-Z
		// (or the toolbar Undo button) restores their previous setup if the AI
		// re-write isn't what they wanted.
		query.captureForUndo();
		if (payload.queryModel) {
			query.current.queryModel = payload.queryModel as typeof query.current.queryModel;
			query.current.type = 'QUERYMODEL';
			query.current.mdx = '';
			query.current.name = '';
		} else {
			query.current.mdx = payload.mdx;
			query.current.type = 'MDX';
			query.current.name = '';
		}
		await query.run();
	}

	// Tab labels derived from each tab's saved-path snapshot. The active
	// tab reads the live query store, others read their captured
	// savedPath. Dirty marker on the active tab driven by live query.dirty;
	// other tabs read their snapshotted dirty flag.
	function deriveTabLabel(path: string | null, pendingName?: string | null): string {
		if (path) {
			const base = path.split('/').pop() ?? path;
			return base.endsWith('.saiku') ? base.slice(0, -'.saiku'.length) : base;
		}
		// Unsaved: show the user-typed name (from a tab-strip rename or a
		// duplicate's "<name> copy" seed) when present, else the neutral label.
		if (pendingName && pendingName.trim()) return pendingName.trim();
		return i18n.t('workspace.unsavedQuery');
	}

	function tabLabelFor(i: number): string {
		if (i === tabs.activeIndex) return deriveTabLabel(query.savedPath, query.pendingName);
		return deriveTabLabel(tabs.list[i].query.savedPath, tabs.list[i].query.pendingName);
	}

	function tabTitleFor(i: number): string {
		if (i === tabs.activeIndex) {
			return query.savedPath ?? i18n.t('workspace.unsavedQuery');
		}
		return tabs.list[i].query.savedPath ?? i18n.t('workspace.unsavedQuery');
	}

	function tabDirtyFor(i: number): boolean {
		if (i === tabs.activeIndex) return query.dirty;
		return tabs.list[i].query.dirty;
	}

	/** Add a new in-app tab. The outgoing tab's state is snapshotted by
	 *  the tabs store so we can come back to it; the new one starts
	 *  blank (no cube, no query). */
	function handleNewTab(): void {
		tabs.newTab();
		// After newTab() the live stores reflect the blank snapshot —
		// clear the URL ?q= param so it doesn't immediately re-hydrate
		// from the previous tab.
		if (typeof window !== 'undefined') {
			const url = new URL(window.location.href);
			if (url.searchParams.has('q')) {
				url.searchParams.delete('q');
				window.history.replaceState(null, '', url.toString());
			}
		}
	}

	function handleSwitchTab(i: number): void {
		tabs.switchTo(i);
	}

	function handleCloseTab(i: number, e: MouseEvent): void {
		e.stopPropagation();
		doCloseTab(i);
	}

	/** Close tab {@code i}, confirming first when it carries unsaved changes.
	 *  Shared by the tab's × affordance and the context-menu Close item. */
	function doCloseTab(i: number): void {
		if (tabs.list.length <= 1) return;
		const dirty = tabDirtyFor(i);
		if (dirty) {
			const ok = window.confirm(i18n.t('confirm.discardUnsaved') ?? 'Discard unsaved changes?');
			if (!ok) return;
		}
		tabs.closeTab(i);
	}

	// --- Tab context menu + inline rename (#1571) ---

	type TabMenuId = 'rename' | 'duplicate' | 'close';

	let tabMenu = $state<{ open: boolean; x: number; y: number; index: number }>({
		open: false,
		x: 0,
		y: 0,
		index: 0
	});

	/** Index of the tab currently in inline-rename mode, or null. */
	let renamingIndex = $state<number | null>(null);
	let renameValue = $state<string>('');

	let tabMenuItems = $derived<ContextMenuItem[]>([
		{ id: 'rename', label: i18n.t('tab.rename') },
		{ id: 'duplicate', label: i18n.t('tab.duplicate') },
		{ id: 'close', label: i18n.t('tab.close'), danger: true }
	]);

	function openTabMenu(i: number, e: MouseEvent): void {
		e.preventDefault();
		e.stopPropagation();
		tabMenu = { open: true, x: e.clientX, y: e.clientY, index: i };
	}

	function onTabMenuPick(id: string): void {
		const i = tabMenu.index;
		if (id === ('rename' satisfies TabMenuId)) {
			beginRename(i);
		} else if (id === ('duplicate' satisfies TabMenuId)) {
			handleDuplicateTab(i);
		} else if (id === ('close' satisfies TabMenuId)) {
			doCloseTab(i);
		}
	}

	/** Enter inline-rename mode for tab {@code i}, seeding the input with its
	 *  current label. The rendered <input> uses the `autofocus` attribute
	 *  (no $effect) so opening it can't trip effect_update_depth_exceeded. */
	function beginRename(i: number): void {
		renameValue = tabLabelFor(i);
		renamingIndex = i;
	}

	function cancelRename(): void {
		renamingIndex = null;
	}

	async function commitRename(i: number): Promise<void> {
		// Guard re-entrancy: Enter commits then unmounts the input, whose blur
		// would call this again; Escape nulls renamingIndex before blur fires.
		// Bail unless this tab is still the one being renamed.
		if (renamingIndex !== i) return;
		const value = renameValue.trim();
		renamingIndex = null;
		if (!value) return;
		try {
			const res = await tabs.rename(i, value);
			// Only the move-a-file path toasts — the unsaved path silently updates
			// the label/pending name (the next Save surfaces it).
			if (res.saved) toasts.success(i18n.t('toast.renamed'), res.path);
		} catch (err) {
			toasts.danger(i18n.t('toast.renameFailed'), err instanceof Error ? err.message : String(err));
		}
	}

	/** Duplicate tab {@code i}. Switch to it first so the store's
	 *  duplicateActive() copies the intended tab's live state. */
	function handleDuplicateTab(i: number): void {
		tabs.switchTo(i);
		tabs.duplicateActive();
	}

	// Guard so we don't immediately overwrite the URL before (or during) hydrate.
	let hydrated = $state(false);

	onMount(() => {
		if (typeof window === 'undefined') return;
		// Re-probe both the AI Ask and Email Me This health flags on workspace
		// mount. Each store's constructor fires at module load, which can race
		// with session-cookie setup on a cold load — a 401 there latches
		// configured=false for the session and hides the Sparkles button (AI Ask)
		// or Email Me This button even after the backend is properly configured.
		// Re-probing here guarantees we ask again with credentials in place.
		void aiAskHealth.refresh();
		void mailHealth.refresh();
		const params = new URLSearchParams(window.location.search);
		const token = params.get('q');
		if (token) {
			const decoded = deserializeQueryFromHash(token);
			if (decoded) {
				// Mirror the cube into the selection store so the sidebar and
				// QueryCanvas $effects agree on what's active.
				selection.select(decoded.query.cube);
				query.hydrate(decoded.query as ThinQuery);
				query.viewMode = (decoded.view.viewMode as typeof query.viewMode) ?? 'grid';
				query.chartType = (decoded.view.chartType as ChartType) ?? 'bar';
				query.chartOptions = (decoded.view.chartOptions as ChartOptions) ?? {
					...DEFAULT_CHART_OPTIONS
				};
				if (query.hasRunnableShape()) void query.run();
			}
			hydrated = true;
			return;
		}

		// saiku-cloud#450 — starter-cube bootstrap. When the launch URL
		// carries the four starterCube* params, resolve the cube via the
		// discover endpoint, pick first measure + first dim level
		// (preferring a time-typed hierarchy), and hydrate the workbench
		// with a fully-formed query model. Customer can immediately drag
		// a different measure / drill / filter — Studio re-fires the query
		// on every interaction. No flat MDX preload — that would freeze
		// the workbench because there's no MDX → model inverse parser.
		const ref = parseStarterCubeRef(params);
		if (ref) {
			void hydrateStarterCube(ref).finally(() => {
				hydrated = true;
			});
			return;
		}

		hydrated = true;
	});

	// Cmd/Ctrl+Z → undo; Cmd/Ctrl+Shift+Z (or Ctrl+Y on Windows) → redo.
	// Wired at window level so the shortcut works regardless of which workspace
	// surface has focus, but skipped while the user is typing in an
	// input/textarea/contentEditable so we don't fight native text-undo (the
	// AI Ask drawer's textarea most notably). Registered as its own $effect so
	// it doesn't depend on the onMount branch the user happens to land on.
	$effect(() => {
		if (typeof window === 'undefined') return;
		const keyHandler = (e: KeyboardEvent) => {
			const cmd = e.metaKey || e.ctrlKey;
			const key = e.key.toLowerCase();
			if (!cmd || (key !== 'z' && key !== 'y')) return;
			const target = e.target as HTMLElement | null;
			const tag = target?.tagName?.toLowerCase();
			if (tag === 'input' || tag === 'textarea' || target?.isContentEditable) return;
			const isRedo = (key === 'z' && e.shiftKey) || key === 'y';
			e.preventDefault();
			if (isRedo) {
				if (query.canRedo) query.redo();
			} else {
				if (query.canUndo) query.undo();
			}
		};
		window.addEventListener('keydown', keyHandler);
		return () => window.removeEventListener('keydown', keyHandler);
	});

	/** Resolve starter-cube ref → SaikuCube → metadata → query.hydrate.
	 *  Every failure falls through silently: a malformed or missing cube
	 *  must not show an error toast, just leave the empty workbench
	 *  rendered as if the URL params were absent. */
	async function hydrateStarterCube(ref: ReturnType<typeof parseStarterCubeRef>): Promise<void> {
		if (!ref) return;
		try {
			// Ensure the connection tree is loaded. The session+CubePicker
			// mount sequence usually does this, but the timing is racey and
			// we don't want to depend on it.
			if (!datasources.loaded) {
				await datasources.load(session.username);
			}
			const cube = findCubeByRef(datasources.connections, ref);
			if (!cube) return;
			// Fetch dimensions + measures (cached after first call).
			const metadata = await datasources.metadata(session.username, cube);
			const pick = pickStarterMeasureAndLevel(metadata.measures, metadata.dimensions);
			if (!pick) return;

			// Hydrate the query model the same way a manual chip-drop would:
			// initFor(cube) → addMeasure → includeLevel(ROWS, drop). Studio
			// sees an ordinary user-built query, so drag/drill/filter all
			// work natively from this starting point.
			selection.select(cube);
			query.initFor(cube);
			query.addMeasure(pick.measure);
			query.includeLevel('ROWS', pick.drop);
			if (query.hasRunnableShape()) void query.run();
		} catch (err) {
			console.warn('saiku: starter-cube hydrate failed, falling back to empty workbench', err);
		}
	}

	/** Debounced write-back. history.replaceState — not pushState — so the
	 *  back button doesn't step through every chip drop. */
	let urlTimer: ReturnType<typeof setTimeout> | null = null;
	function writeUrl() {
		if (typeof window === 'undefined') return;
		if (!query.current) {
			// No query yet: clear the ?q= param if present.
			const url = new URL(window.location.href);
			if (url.searchParams.has('q')) {
				url.searchParams.delete('q');
				window.history.replaceState(null, '', url.toString());
			}
			return;
		}
		const token = serializeQueryToHash(query.current, {
			viewMode: query.viewMode,
			chartType: query.chartType,
			chartOptions: query.chartOptions
		});
		const url = new URL(window.location.href);
		url.searchParams.set('q', token);
		window.history.replaceState(null, '', url.toString());
	}

	$effect(() => {
		// Depend on the mutation-tracking fields so we refire on any change.
		const _c = query.current;
		const _d = query.dirtyCount;
		const _v = query.viewMode;
		const _t = query.chartType;
		const _o = query.chartOptions;
		const _s = query.savedPath;
		// `_*` are reactive reads; silence unused-var lint.
		void _c;
		void _d;
		void _v;
		void _t;
		void _o;
		void _s;
		if (!hydrated) return;
		// Active-tab label is derived from live query.savedPath directly
		// in tabLabelFor(); inactive-tab labels read each tab's snapshot,
		// which is captured at switch/close time via captureActive().
		// No need to write tabs.list from this effect — doing so caused
		// an effect_update_depth_exceeded loop because the write re-fires
		// every $effect that read tabs.list during the same tick.
		if (urlTimer) clearTimeout(urlTimer);
		urlTimer = setTimeout(writeUrl, 300);
	});
</script>

<div class="workspace" class:workspace--embed={embed.active}>
	{#if !embed.active}
		<aside class="workspace__sidebar">
			<div class="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-4">
				<CubePicker username={session.username} />
				<!-- Sidebar body branches on selection.mode. Picking a cube surfaces the existing
             MDX schema tree; picking an Ossie connection surfaces the semantic-model tree
             (datasets / metrics / relationships) instead. -->
				{#if selection.mode === 'ossie'}
					<OssieSchemaTree username={session.username} />
				{:else}
					<DimensionList username={session.username} />
				{/if}
			</div>
			<div class="workspace__sidebar-footer">
				<PrefsMenu />
				<Button variant="outline" onclick={() => (aboutOpen = true)}
					>{i18n.t('modal.about.title')}</Button
				>
			</div>
		</aside>
	{/if}
	<section class="workspace__main">
		{#if !embed.active}
			<div class="tabset" role="tablist">
				{#each tabs.list as t, i (t.id)}
					{#if renamingIndex === i}
						<!-- Inline rename: the tab label becomes a text input. Enter
                 commits, Escape cancels, blur commits (guarded against the
                 double-fire that Enter/Escape unmount would otherwise cause).
                 `autofocus` (no $effect) sidesteps effect_update_depth. -->
						<div class="tab tab--active tab--renaming">
							<!-- svelte-ignore a11y_autofocus -->
							<input
								class="tab__rename-input"
								bind:value={renameValue}
								aria-label={i18n.t('tab.rename')}
								placeholder={i18n.t('tab.renamePlaceholder')}
								onkeydown={(e) => {
									if (e.key === 'Enter') {
										e.preventDefault();
										void commitRename(i);
									} else if (e.key === 'Escape') {
										e.preventDefault();
										cancelRename();
									}
								}}
								onblur={() => void commitRename(i)}
								autofocus
							/>
						</div>
					{:else}
						<button
							type="button"
							class="tab"
							class:tab--active={i === tabs.activeIndex}
							role="tab"
							aria-selected={i === tabs.activeIndex}
							title={tabTitleFor(i)}
							onclick={() => handleSwitchTab(i)}
							ondblclick={() => beginRename(i)}
							oncontextmenu={(e) => openTabMenu(i, e)}
						>
							<span class="tab__label">{tabLabelFor(i)}</span>
							{#if tabDirtyFor(i)}<span class="text-primary" aria-label="unsaved changes">•</span
								>{/if}
							{#if tabs.list.length > 1}
								<span
									class="tab__close"
									role="button"
									tabindex="0"
									aria-label={i18n.t('tab.close')}
									onclick={(e) => handleCloseTab(i, e)}
									onkeydown={(e) => {
										if (e.key === 'Enter' || e.key === ' ')
											handleCloseTab(i, e as unknown as MouseEvent);
									}}>×</span
								>
							{/if}
						</button>
					{/if}
				{/each}
				<button
					type="button"
					class="tab text-fg-subtle"
					aria-label={i18n.t('toast.newQuery')}
					title={i18n.t('toast.newQuery')}
					onclick={handleNewTab}>+</button
				>
			</div>
			<!-- MDX toolbar carries dozens of MDX-flavoured affordances (Show MDX, Swap Axes,
           Non-empty toggle, Chart-type buttons, Ask AI, Export). None of them make sense
           for Ossie shelf-state queries; hide the whole toolbar when Ossie mode is
           active so the user can't click into confused states. The Ossie canvas below
           carries its own minimal toolbar (Save / Load / Run). -->
			{#if selection.mode !== 'ossie'}
				<WorkspaceToolbar
					onAskAi={() => (aiDrawerOpen = true)}
					aiConfigured={aiAskHealth.configured}
				/>
			{/if}
		{/if}
		<!-- Canvas branches on selection.mode too so the workbench renders the SQL-flavoured
         shelves + result table when an Ossie connection is picked, and the existing MDX
         canvas otherwise. Toolbar / tabset above stay shared so the user experience is
         uniform across both. -->
		{#if selection.mode === 'ossie'}
			<OssieQueryCanvas />
		{:else}
			<QueryCanvas />
		{/if}
	</section>
</div>

{#if !embed.active && aiAskHealth.configured && selection.mode !== 'ossie'}
	<!-- AI drawer speaks MDX/olap4j — hide it in Ossie mode so an Ossie user can't open a
       drawer that would generate an invalid query for their selected connection. Once
       the AI query surface learns SQL over Ossie models this guard can drop. -->
	<AiQueryDrawer
		open={aiDrawerOpen}
		onClose={() => (aiDrawerOpen = false)}
		onEditInCanvas={(payload) => void handleEditInCanvas(payload)}
		onApplyViewChange={handleApplyViewChange}
	/>
{/if}

<!-- Full-screen dashboard-build loader. Mounted at the page level, OUTSIDE the
     AI drawer, so a drawer unmount/remount (window resize) can't hide it while
     the ~1-2 min build runs. Self-gates on the build store. -->
<AiDashboardBuildingOverlay />

<!-- Right-click tab menu (#1571): Rename / Duplicate / Close. -->
<ContextMenu
	open={tabMenu.open}
	x={tabMenu.x}
	y={tabMenu.y}
	items={tabMenuItems}
	onPick={onTabMenuPick}
	onClose={() => (tabMenu = { ...tabMenu, open: false })}
/>

<Modal
	title={i18n.t('modal.about.title')}
	open={aboutOpen}
	size="sm"
	onClose={() => (aboutOpen = false)}
>
	<p>{i18n.t('modal.about.tagline')}</p>
	<!-- Build version surfaced from /rest/saiku/admin/version, populated
       on app boot by platform.loadVersion(). The Maven build filters
       version.properties so the value tracks ${project.version} on the
       deployed jar — no manual sync needed. -->
	{#if platform.version}
		<p class="about-version">
			{i18n.t('modal.about.version')}
			<code>{platform.version}</code>
		</p>
	{/if}
	<p>
		<strong>{session.username}</strong> · {session.roles.join(', ')}
	</p>
	<p>
		<a
			href="https://join.slack.com/t/saikucloud/shared_invite/zt-3yor7kgiv-YUPhqK0pvd4WljctIQVOTA"
			target="_blank"
			rel="noopener noreferrer">{i18n.t('modal.about.community')}</a
		>
	</p>
	{#snippet footer()}
		<Button onclick={() => (aboutOpen = false)}>{i18n.t('modal.close')}</Button>
	{/snippet}
</Modal>

<style>
	.workspace {
		flex: 1;
		min-height: 0;
		display: grid;
		/* minmax(0, 1fr) (not bare 1fr) so the main column can shrink below its
       content's min-width — otherwise the whole workspace is forced wider
       than the viewport and the page scrolls horizontally at narrow widths
       (#1176). */
		grid-template-columns: 300px minmax(0, 1fr);
		gap: 1px;
		background: hsl(var(--border));
		overflow: hidden;
	}
	.workspace--embed {
		grid-template-columns: 1fr;
		gap: 0;
	}
	.workspace__sidebar,
	.workspace__main {
		background: hsl(var(--bg));
		min-height: 0;
		min-width: 0;
		overflow: hidden;
	}
	.workspace__sidebar {
		display: flex;
		flex-direction: column;
	}
	.workspace__sidebar-footer {
		padding: var(--space-3) var(--space-4);
		border-top: 1px solid hsl(var(--border-strong));
		background: hsl(var(--bg-subtle));
		display: flex;
		align-items: center;
		gap: var(--space-2);
	}
	.workspace__main {
		display: flex;
		flex-direction: column;
	}
	.tabset {
		display: flex;
		align-items: stretch;
		padding: 0 var(--space-2);
		border-bottom: 1px solid hsl(var(--border));
		background: hsl(var(--bg-muted));
		/* .workspace__main has overflow:hidden; without horizontal scroll
       here a third (or further) tab gets clipped past the right edge
       and the "+" button becomes unreachable, which presents to the
       user as "can't open more than 2 tabs". Allow the strip to scroll
       and make each tab non-shrinking so the labels don't compact
       into an unreadable column either. */
		overflow-x: auto;
		overflow-y: hidden;
		scrollbar-width: thin;
	}
	.tab {
		display: inline-flex;
		align-items: center;
		gap: var(--space-2);
		padding: var(--space-2) var(--space-3);
		color: hsl(var(--fg-muted));
		border-bottom: 2px solid transparent;
		background: transparent;
		border-top: 0;
		border-left: 0;
		border-right: 0;
		font: inherit;
		font-size: var(--fs-sm);
		cursor: pointer;
		max-width: 18rem;
		flex-shrink: 0;
	}
	.tab:hover:not(.tab--active) {
		color: hsl(var(--fg));
		background: color-mix(in srgb, hsl(var(--bg)) 60%, transparent);
	}
	.tab--active {
		color: hsl(var(--fg));
		border-bottom-color: hsl(var(--primary));
	}
	.tab__label {
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
		max-width: 12rem;
	}
	.tab--renaming {
		/* Keep the same footprint as a normal tab so the strip doesn't jump
       when the label flips to an input. */
		cursor: text;
	}
	.tab__rename-input {
		width: 10rem;
		max-width: 12rem;
		padding: 2px 4px;
		background: hsl(var(--bg));
		border: 1px solid hsl(var(--border-strong));
		border-radius: var(--radius-sm);
		color: hsl(var(--fg));
		font: inherit;
		font-size: var(--fs-sm);
		outline: none;
	}
	.tab__rename-input:focus {
		border-color: hsl(var(--primary));
	}
	.tab__close {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 1.25rem;
		height: 1.25rem;
		border-radius: 50%;
		color: hsl(var(--fg-muted));
		font-size: 1rem;
		line-height: 1;
		user-select: none;
	}
	.tab__close:hover {
		background: color-mix(in srgb, hsl(var(--danger)) 18%, transparent);
		color: hsl(var(--danger));
	}
</style>

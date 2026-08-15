<!--
  Admin › Cube Designer — the OSS home for the visual cube/schema designer
  ported from saiku-cloud. Dedicated dynamic route (mirrors the schema-generator)
  so the heavy canvas app gets a full page + a datasource context.

  This route is the HOST: it creates the store, provides the OSS backend via
  context (setCubeDesignerBackend), profiles the datasource into the source
  sidebar, and owns Save (emit Mondrian 4 XML → admin schema upload). The
  designer components themselves are host-agnostic.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { SchemaCanvasStore } from '$lib/cube-designer/state.svelte';
	import SchemaCanvasView from '$lib/cube-designer/SchemaCanvasView.svelte';
	import WorkbenchView from '$lib/cube-designer/WorkbenchView.svelte';
	import { setCubeDesignerBackend, setCubeDesignerAI } from '$lib/cube-designer/backend';
	import {
		ossCubeDesignerBackend,
		ossCubeDesignerAI,
		fetchDatasourceSchema
	} from '$lib/cube-designer/oss-backend';
	import { hydrateFromMondrianXml } from './edit-load';
	import { exportToMondrianXml } from '$lib/cube-designer/mondrian-export';
	import { parseProfileTables } from '$lib/cube-designer/profile-types';
	import type { SourceTableCandidate } from '$lib/cube-designer/types';
	import { adminSchemas, adminDatasources, type AdminDatasource } from '$lib/api/admin';
	import { buildLaunchUrl, repositorySchemaPath, resolveSchemaName } from './publish';
	import { platform } from '$lib/stores/platform.svelte';
	import { trackDemo } from '$lib/analytics/demoAnalytics';
	import { Button } from '$lib/components/ui';
	import { FeedbackBanner } from '$lib/design-system';
	import { LayoutGrid, ArrowLeftRight, Sigma, CheckCircle2 } from '@lucide/svelte';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();
	const dataSourceId = data.dataSourceId;

	// Store keyed on the datasource; provide the OSS backend to the designer subtree.
	const store = new SchemaCanvasStore(dataSourceId);
	setCubeDesignerBackend(ossCubeDesignerBackend);
	setCubeDesignerAI(ossCubeDesignerAI);

	let sourceError = $state<string | null>(null);
	let saving = $state(false);
	let saveMsg = $state<{ tone: 'success' | 'error'; text: string } | null>(null);

	// The datasource row we are designing against. Needed for two things Save has to do
	// beyond writing the file: the PUT that attaches the schema, and the *registered*
	// connection name (the server prefixes it with the workspace, so it is not the
	// datasource id) that Studio's starterCube contract wants.
	let datasource = $state<AdminDatasource | null>(null);

	// `doc.updatedAt` is bumped by every store mutation, so remembering its value at save
	// time is all the dirty-tracking the Confirm-cube rail needs. Without this the pane's
	// `isSchemaDirty` sat at its `true` default forever and the "Open in Saiku" button was
	// permanently stuck in its disabled branch (saiku#1859).
	// Force-remount key for the workbench. Bumped only where cubes are written into the doc
	// from OUTSIDE WorkbenchView — currently just the edit-mode hydrate. See the `#key` below.
	let workbenchKey = $state<number>(0);

	let savedAtStamp = $state<string | null>(null);
	const isSchemaDirty = $derived(savedAtStamp === null || store.doc.updatedAt !== savedAtStamp);

	// Schema name — surfaced in the header so it is editable and visible BEFORE save.
	// It becomes the `<Schema name>` attribute, the repository filename and the catalog
	// name users see in Studio, so leaving it to an invisible export-time default was how
	// every designed cube ended up called "Untitled" (saiku#1861).
	let schemaName = $state<string>('');
	const effectiveSchemaName = $derived(resolveSchemaName(schemaName, dataSourceId));

	/**
	 * Commit a typed schema name to the doc immediately, so the XML preview, the Try-a-query
	 * payload and the export all agree with what the header shows — rather than only catching
	 * up at save time.
	 */
	function renameSchema(next: string): void {
		schemaName = next;
		const resolved = resolveSchemaName(next, dataSourceId);
		if (store.doc.label !== resolved) store.setLabel(resolved);
	}

	/** Studio URL for a cube in the Confirm-cube rail, once the schema is published. */
	function launchUrlFor(cube: { name: string }): string {
		return buildLaunchUrl({
			connection: datasource?.connectionName ?? datasource?.name ?? dataSourceId,
			schema: effectiveSchemaName,
			cube: cube.name
		});
	}

	// saiku#1636: on a public demo, don't let visitors persist a (possibly broken)
	// schema that would take cubes down for everyone. Save is disabled in demo mode.
	const demoMode = $derived(platform.capabilities?.demoMode === true);

	const MODES = [
		{ m: 'canvas', label: 'Schema Canvas', Icon: LayoutGrid },
		{ m: 'workbench', label: 'Dimensions & Hierarchies', Icon: ArrowLeftRight },
		{ m: 'facts', label: 'Facts & Measures', Icon: Sigma },
		{ m: 'validate', label: 'Confirm cube', Icon: CheckCircle2 }
	] as const;

	onMount(async () => {
		if (!platform.capabilities) await platform.loadCapabilities();
		trackDemo('cube-designer', 'open');
		store.switchConnection(dataSourceId);
		store.sourceLoading = true;
		store.sourceError = null;
		try {
			const r = await ossCubeDesignerBackend.profileConnection(dataSourceId);
			if (!r.ok) {
				throw new Error(`could not profile the datasource (HTTP ${r.status})`);
			}
			const profiled = parseProfileTables(await r.text());
			const onCanvas = store.tableIdentitiesOnCanvas;
			store.sourceTables = profiled.map((t): SourceTableCandidate => ({
				schema: t.schema,
				name: t.name,
				columns: t.columns,
				onCanvas: onCanvas.has(t.schema ? `${t.schema}.${t.name}` : t.name)
			}));
		} catch (e) {
			sourceError = e instanceof Error ? e.message : 'could not profile the datasource';
		} finally {
			store.sourceLoading = false;
		}
		await loadDatasource();
		// saiku#1634 edit mode: if this datasource already has a Mondrian schema,
		// hydrate the canvas from it. Runs after profiling so the importer can
		// enrich imported tables against the live source catalog.
		await loadExistingSchema();
		// Seed the header field last, so an imported schema's own label wins over the
		// generated default.
		schemaName = resolveSchemaName(store.doc.label, dataSourceId);
	});

	/**
	 * Load the datasource row so Save can attach to it and Studio links can be built.
	 *
	 * The route param is the datasource NAME, not its id — see `generateSchemaHref`, and the
	 * server's `/cube-designer/schema/{dataSourceId}` which resolves through
	 * `DatasourceService.getDatasource` (keyed on name). Matching on id here found nothing,
	 * because the ids are UUIDs. The id is still what the update PUT needs, so we keep the
	 * whole row rather than just the key.
	 */
	async function loadDatasource() {
		try {
			const all = await adminDatasources.list();
			datasource =
				all.find((d) => d.name === dataSourceId) ??
				all.find((d) => d.connectionName === dataSourceId) ??
				all.find((d) => d.id === dataSourceId) ??
				null;
			if (!datasource) {
				sourceError = `Datasource "${dataSourceId}" was not found. Save can still write the schema file, but will not be able to attach it.`;
			}
		} catch (e) {
			sourceError = e instanceof Error ? e.message : 'could not load the datasource';
		}
	}

	/**
	 * Fetch the datasource's attached Mondrian schema (if any) and load it onto
	 * the canvas. A 404 (no schema attached) is the new-cube path — leave the
	 * canvas blank. A parse/read error surfaces in the source-error banner.
	 */
	async function loadExistingSchema() {
		try {
			const r = await fetchDatasourceSchema(dataSourceId);
			if (!r.ok) return; // 404 ⇒ no schema attached: start a new cube, blank canvas
			const body = (await r.json()) as { mondrianXml?: string; label?: string };
			const xml = body.mondrianXml ?? '';
			if (!xml) return;
			hydrateFromMondrianXml(store, xml, dataSourceId);
			// Mark the canvas as editing a saved schema so the workbench shows saved
			// (not draft) state; Save then writes back under this name.
			store.doc.lineageId = body.label?.trim() || dataSourceId;
			// A schema loaded straight from the server is saved AND clean, so baseline the
			// dirty stamp here too. Without this, opening an already-published cube for edit
			// would show it as dirty and keep "Open in Saiku" disabled until a pointless save.
			savedAtStamp = store.doc.updatedAt;
			// The workbench has already mounted against the pre-hydrate (blank) doc by now.
			workbenchKey += 1;
		} catch (e) {
			sourceError = e instanceof Error ? e.message : 'could not load the existing schema';
		}
	}

	/**
	 * Publish the designed schema — upload, attach, refresh.
	 *
	 * saiku#1860: this used to stop after the upload. The XML landed in the repository but
	 * nothing pointed the datasource at it, so the cube never appeared in Studio and the only
	 * way to finish the job was to know to go and paste `/datasources/<name>.xml` into the
	 * datasource by hand. Attaching is not an extra convenience — without it "Save" does not
	 * produce a queryable cube, which is the entire point of the designer.
	 */
	async function save() {
		if (demoMode) return; // saving disabled in demo mode
		saving = true;
		saveMsg = null;

		// Commit the header's name to the doc BEFORE exporting: `exportToMondrianXml` reads
		// `doc.label` for the `<Schema name>` attribute, and that name has to match the one we
		// upload under or the file and the catalog disagree.
		const name = effectiveSchemaName;
		if (store.doc.label !== name) store.setLabel(name);

		let xml: string;
		try {
			xml = exportToMondrianXml(store.doc);
		} catch (e) {
			saveMsg = {
				tone: 'error',
				text: e instanceof Error ? e.message : 'The cube is not ready to save yet.'
			};
			saving = false;
			return;
		}

		try {
			await adminSchemas.upload(name, xml);
		} catch (e) {
			saveMsg = { tone: 'error', text: e instanceof Error ? e.message : 'Save failed.' };
			saving = false;
			return;
		}

		// From here the file IS saved. Attach failures must say so rather than reading as a
		// total failure, because re-running Save would otherwise look like the only recourse.
		store.doc.lineageId = name;
		savedAtStamp = store.doc.updatedAt;

		if (!datasource) {
			saveMsg = {
				tone: 'error',
				text: `Saved schema "${name}", but the datasource could not be loaded, so it was not attached. Set its schema to ${repositorySchemaPath(name)} in Admin › Datasources.`
			};
			saving = false;
			return;
		}

		try {
			const path = repositorySchemaPath(name);
			if (datasource.schemaName !== path) {
				const updated = await adminDatasources.update({ ...datasource, schemaName: path });
				if (updated) datasource = updated;
			}
			// Reopen the connection so the freshly attached schema is live without a restart.
			await adminDatasources.refresh(datasource.name);
			saveMsg = {
				tone: 'success',
				text: `Published "${name}" — attached to ${datasource.name} and ready to query.`
			};
		} catch (e) {
			saveMsg = {
				tone: 'error',
				text: `Saved schema "${name}", but attaching it to the datasource failed${
					e instanceof Error ? `: ${e.message}` : ''
				}. Set the datasource's schema to ${repositorySchemaPath(name)} in Admin › Datasources.`
			};
		} finally {
			saving = false;
		}
	}
</script>

<!-- flex-1 + min-w/h-0 so the designer fills the layout's <main> (a flex row)
     across the full width AND height, instead of h-svh (which sized to the
     viewport and left a right-hand gap / overran the topbar). -->
<div class="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
	<header class="flex shrink-0 flex-wrap items-center gap-2 border-b border-border px-3 py-2">
		<h1 class="mr-2 text-sm font-semibold text-foreground">
			Cube Designer <span class="text-muted-foreground">· {dataSourceId}</span>
		</h1>
		<!-- Segmented mode tabs — one outer border, siblings share edges; active
         tab is an inset red ring + glow (matches saiku-cloud's CanvasHeader). -->
		<nav
			class="flex items-stretch overflow-hidden rounded border border-border bg-background"
			role="tablist"
			aria-label="Designer mode"
		>
			{#each MODES as { m, label, Icon } (m)}
				{@const active = store.mode === m}
				<button
					type="button"
					role="tab"
					aria-selected={active}
					aria-label={label}
					title={label}
					class="relative inline-flex h-9 items-center justify-center gap-1.5 border-l border-border px-3 text-xs font-medium transition-colors first:border-l-0 {active
						? 'z-10 bg-card text-primary shadow-[0_0_12px_hsl(var(--primary)/0.35)] ring-2 ring-primary/60 ring-inset'
						: 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'}"
					onclick={() => store.setMode(m)}
				>
					<Icon class="h-3.5 w-3.5" aria-hidden="true" />
					<span>{label}</span>
				</button>
			{/each}
		</nav>
		<div class="ml-auto flex items-center gap-2">
			<!-- Schema name.  Visible and editable up front because it becomes the catalog
			     name users see in Studio — leaving it to an export-time default is how every
			     designed cube came out called "Untitled" (saiku#1861). -->
			<label class="flex items-center gap-1.5 text-xs text-muted-foreground">
				<span>Schema</span>
				<!-- Deliberately NOT `bind:value` + `$effect`: pushing the name into the store
				     from an effect would read and write `doc.label` in the same tick, which is
				     the documented route to `effect_update_depth_exceeded` in this codebase.
				     An input handler commits it once, on the event. -->
				<input
					type="text"
					value={schemaName}
					oninput={(e) => renameSchema(e.currentTarget.value)}
					placeholder={`${dataSourceId}-cube`}
					aria-label="Schema name"
					data-testid="cube-designer-schema-name"
					class="h-8 w-44 rounded border border-border bg-background px-2 text-xs text-foreground"
				/>
			</label>
			<Button
				size="sm"
				onclick={save}
				disabled={saving || demoMode}
				title={demoMode ? 'Saving is disabled in demo mode' : undefined}
			>
				{saving ? 'Saving…' : 'Save'}
			</Button>
			<Button size="sm" variant="ghost" onclick={() => goto('/admin?tab=datasources')}>Back</Button>
		</div>
	</header>

	{#if saveMsg}
		<div class="shrink-0 px-3 pt-2">
			<FeedbackBanner tone={saveMsg.tone} size="sm">{saveMsg.text}</FeedbackBanner>
		</div>
	{/if}
	{#if sourceError}
		<div class="shrink-0 px-3 pt-2">
			<FeedbackBanner tone="error" size="sm">{sourceError}</FeedbackBanner>
		</div>
	{/if}

	<div class="flex min-h-0 flex-1 flex-col overflow-hidden">
		{#if store.mode === 'canvas'}
			<SchemaCanvasView {store} />
		{:else}
			<!-- saiku#1863: WorkbenchView reads `doc.cubes` ONCE, on mount, and the edit-mode
			     hydrate resolves asynchronously — well after that. Without a forced remount the
			     workbench kept the blank "Cube 1" it mounted with while the real imported cube
			     sat unseen in the doc, and Save then published that blank over the user's
			     actual schema.

			     Keyed on `workbenchKey`, NOT on `store.workbenchReloadNonce` directly: the
			     workbench's own persistence effect calls `setCubes`, which bumps that nonce, so
			     keying on it would remount the workbench on every edit — and if
			     `cubeFromDoc ∘ cubeToDoc` is ever not an exact round-trip, remount forever. -->
			{#key workbenchKey}
				<WorkbenchView
					{store}
					isSchemaSaved={!!store.doc.lineageId}
					{isSchemaDirty}
					{launchUrlFor}
				/>
			{/key}
		{/if}
	</div>
</div>

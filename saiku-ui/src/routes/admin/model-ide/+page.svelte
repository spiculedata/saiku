<!--
  Model IDE (saiku#1428): two-pane Monaco editor over the repository's models.

  Two file kinds share this page:

  - Mondrian schema XML (Admin › Schemas files), loaded/saved via `adminSchemas`, with the
    `mondrian-xml` language (custom id, structural lint, catalogue-fed completion). This was
    the slice PR for #2061.
  - Ossie (M4) semantic-model YAML, one entry per OSSIE datasource, loaded/saved via the raw
    YAML read/write endpoints (`/rest/saiku/admin/ossie/schema/{datasource}`, saiku#1121),
    with the `yaml` language (Monaco's bundled tokenizer), a structural lint
    (`ossie-yaml-lint`), and completion fed by the live discover model
    (`ossie-yaml-completions`). The server validates the document again on save.

  Follow-up (still open on the issue): a git-branch preview/merge flow (temporary
  saiku-home overlay + discover-refresh against it) and deprecating the older Admin ›
  Schemas upload form.
-->
<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import MonacoEditor from '$lib/components/MonacoEditor.svelte';
	import EmptyState from '$lib/components/EmptyState.svelte';
	import ConfirmModal from '$lib/modals/ConfirmModal.svelte';
	import { Button } from '$lib/components/ui';
	import { FeedbackBanner, Skeleton } from '$lib/design-system';
	import {
		adminSchemas,
		adminDatasources,
		type AdminSchema,
		type AdminDatasource
	} from '$lib/api/admin';
	import { getOssieSchemaYaml, saveOssieSchemaYaml } from '$lib/api/adminOssieSchema';
	import { fetchOssieModel, type OssieModel } from '$lib/api/ossie';
	import { getCurrentSession, type SaikuSession } from '$lib/api/session';
	import { fetchCubeSummaries, fetchCubeDetail } from '$lib/api/aiCubes';
	import { MondrianCatalogue, extractCubeNames } from '$lib/monaco/mondrian-catalogue';
	import { lintMondrianXml, type LintIssue } from '$lib/monaco/mondrian-xml-lint';
	import { lintOssieYaml } from '$lib/monaco/ossie-yaml-lint';
	import type { OssieCatalogue } from '$lib/monaco/ossie-yaml-completions';
	import { diffCubeNames, type CubeDiff } from './cubeDiff';
	import { bufferKeyFor, mondrianKey, ossieKey } from './bufferKey';
	import { catalogueFor, isStillSelected } from './ossieCatalogue';
	import { FileCode, Plus } from '@lucide/svelte';

	const NEW_SCHEMA_TEMPLATE = `<Schema name="NewSchema">\n  <Cube name="NewCube">\n    <Table name="table_name"/>\n    <Dimension name="Dimension1" foreignKey="fk_column">\n      <Hierarchy hasAll="true" primaryKey="pk_column">\n        <Level name="Level1" column="column_name" uniqueMembers="true"/>\n      </Hierarchy>\n    </Dimension>\n    <Measure name="Measure1" column="amount_column" aggregator="sum"/>\n  </Cube>\n</Schema>\n`;

	/** Starter model for an OSSIE datasource that has no YAML on disk yet (the backend 404s).
	 *  Kept minimal but loadable: one dataset + one field so Save round-trips immediately. */
	function ossieTemplate(datasourceName: string): string {
		return [
			`# Ossie semantic model for datasource "${datasourceName}".`,
			'# The document is validated client-side as you type and again server-side on save',
			'# (docs/ossie-yaml.md documents the conventions layered on top of the base model).',
			'version: 0.2.0.dev0',
			'',
			'semantic_model:',
			`- name: ${datasourceName}`,
			`  description: Semantic model for datasource ${datasourceName}.`,
			'  datasets:',
			'  - name: example_dataset',
			'    source: EXAMPLE_TABLE',
			'    primary_key: [ID]',
			'    description: Replace with your real tables.',
			'    fields:',
			'    - name: id',
			'      expression: {dialects: [{dialect: ANSI_SQL, expression: ID}]}',
			'',
			'  metrics: []',
			'  relationships: []',
			''
		].join('\n');
	}

	// monaco.MarkerSeverity.Error / .Warning — hardcoded rather than importing `monaco-editor`
	// here for its enum, which would pull the package into this file's eager import graph and
	// crash SvelteKit's SSR pass the same way it crashes Vitest's `node` environment (no
	// `window`); see MonacoEditor.svelte's own dynamic `import('monaco-editor')` for why.
	const MARKER_SEVERITY = { error: 8, warning: 4 } as const;

	type Selection =
		{ kind: 'mondrian'; schema: AdminSchema } | { kind: 'ossie'; datasource: AdminDatasource };

	let schemas = $state<AdminSchema[]>([]);
	let datasources = $state<AdminDatasource[]>([]);
	let session = $state<SaikuSession | null>(null);
	let selected = $state<Selection | null>(null);
	let buffers = $state<Record<string, string>>({});
	let baselines = $state<Record<string, string>>({});
	let loadingList = $state(true);
	let loadingFile = $state(false);
	let saving = $state(false);
	let saveMsg = $state<{ tone: 'success' | 'error'; text: string } | null>(null);
	let conflictPending = $state(false);
	let cubeDiffResult = $state<CubeDiff | null>(null);
	let monacoLanguageReady = $state(false);
	let newName = $state('');
	let creating = $state(false);

	// Mutated in place (setSummaries/setDetail); the completion provider reads it through a
	// closure, not through Svelte's reactivity, so it does not need to be `$state`.
	const catalogue = new MondrianCatalogue();
	// Same for the live Ossie model: read by the completion provider via a closure.
	const ossieCatalogue: OssieCatalogue = { datasets: [], datasetFields: {}, metrics: [] };
	// Which datasource the catalogue currently mirrors. Completion and the save-diff
	// snapshot may only use the catalogue when it matches the open file — a stale
	// catalogue from another datasource would otherwise offer foreign dataset names
	// and produce a bogus post-save diff. `null` = empty / unknown.
	let ossieCatalogueDs: string | null = null;

	function clearOssieCatalogue(): void {
		ossieCatalogue.datasets = [];
		ossieCatalogue.datasetFields = {};
		ossieCatalogue.metrics = [];
		ossieCatalogueDs = null;
	}

	const ossieDatasources = $derived(datasources.filter((d) => d.connectiontype === 'OSSIE'));
	const selectedName = $derived(
		selected === null
			? null
			: selected.kind === 'mondrian'
				? selected.schema.name
				: selected.datasource.name
	);
	const bufferKey = $derived(selected ? bufferKeyFor(selected) : '');
	const currentContent = $derived(selected ? (buffers[bufferKey] ?? '') : '');
	const lintIssues = $derived<LintIssue[]>(
		!currentContent
			? []
			: selected?.kind === 'ossie'
				? lintOssieYaml(currentContent)
				: lintMondrianXml(currentContent)
	);
	const hasErrors = $derived(lintIssues.some((i) => i.severity === 'error'));
	const isDirty = $derived(
		selected !== null && currentContent !== (baselines[bufferKey] ?? currentContent)
	);
	const markers = $derived(
		lintIssues.map((i) => ({
			startLineNumber: i.startLine,
			startColumn: i.startColumn,
			endLineNumber: i.endLine,
			endColumn: i.endColumn,
			message: i.message,
			severity: MARKER_SEVERITY[i.severity]
		}))
	);
	/** For Mondrian files: datasources whose attached schema is the file currently open.
	 *  For Ossie files: the datasource itself — the file IS the datasource's model. */
	const linkedDatasources = $derived.by(() => {
		const sel = selected;
		if (sel === null) return [];
		if (sel.kind === 'mondrian') {
			return datasources.filter((d) => d.schemaName === sel.schema.path);
		}
		return [sel.datasource];
	});

	onMount(async () => {
		const [{ registerMondrianXmlLanguage }, { registerOssieYamlLanguage }] = await Promise.all([
			import('$lib/monaco/mondrian-xml-lang'),
			import('$lib/monaco/ossie-yaml-lang'),
			loadAll()
		]);
		registerMondrianXmlLanguage(() => catalogue);
		registerOssieYamlLanguage(() => catalogueFor(selected, ossieCatalogueDs, ossieCatalogue));
		monacoLanguageReady = true;

		const openParam = page.url.searchParams.get('open');
		if (openParam?.startsWith('ossie:')) {
			const ds = ossieDatasources.find((d) => d.name === openParam.slice('ossie:'.length));
			if (ds) await selectOssie(ds);
		} else if (openParam) {
			const match = schemas.find((s) => s.name === openParam);
			if (match) await selectSchema(match);
		}
	});

	async function loadAll(): Promise<void> {
		loadingList = true;
		try {
			const [schemaList, datasourceList, cubeSummaries, currentSession] = await Promise.all([
				adminSchemas.list(),
				adminDatasources.list(),
				fetchCubeSummaries().catch(() => []),
				getCurrentSession()
			]);
			schemas = schemaList;
			datasources = datasourceList;
			session = currentSession;
			catalogue.setSummaries(cubeSummaries);
		} catch (e) {
			saveMsg = { tone: 'error', text: e instanceof Error ? e.message : 'Failed to load.' };
		} finally {
			loadingList = false;
		}
	}

	/** Best-effort: fetch live measure/dimension/level detail for whichever cubes this file
	 *  declares AND the live catalogue already knows about. A cube new to this edit (not yet
	 *  saved+refreshed) simply completes with no measures/dimensions yet — expected, not an
	 *  error, so failures here are swallowed rather than surfaced. */
	async function prefetchCubeDetail(xml: string): Promise<void> {
		const names = extractCubeNames(xml);
		await Promise.all(
			names.map(async (name) => {
				const cube = catalogue.get(name);
				if (!cube) return;
				try {
					const detail = await fetchCubeDetail(cube.cubeId);
					catalogue.setDetail(name, detail);
				} catch {
					// Live schema unavailable for this cube — completion just stays structural.
				}
			})
		);
	}

	/** Best-effort: fetch the live semantic model for an OSSIE datasource into the completion
	 *  catalogue. A datasource whose connection isn't up yet (never opened since boot) fails
	 *  here; completion then falls back to the document's own structure, so failures are
	 *  swallowed rather than surfaced. */
	async function prefetchOssieModel(datasourceName: string): Promise<OssieModel | null> {
		try {
			if (!session?.username) return null;
			const model = await fetchOssieModel(session.username, datasourceName);
			// A late response for a datasource that is no longer open must not overwrite the
			// open file's catalogue (PR #2490 review).
			if (!isStillSelected(selected, datasourceName)) return model;
			ossieCatalogue.datasets = model.datasets.map((d) => d.name);
			ossieCatalogue.datasetFields = Object.fromEntries(
				model.datasets.map((d) => [d.name, d.fields.map((f) => f.name)])
			);
			ossieCatalogue.metrics = model.metrics.map((m) => m.name);
			ossieCatalogueDs = datasourceName;
			return model;
		} catch {
			// Failed fetch: stale entries from another datasource would surface as
			// foreign completions and a bogus save diff — clear rather than keep them.
			if (isStillSelected(selected, datasourceName)) clearOssieCatalogue();
			return null;
		}
	}

	async function selectSchema(s: AdminSchema): Promise<void> {
		selected = { kind: 'mondrian', schema: s };
		afterSelect(`?open=${encodeURIComponent(s.name)}`);
		if (buffers[mondrianKey(s.name)] !== undefined) return; // already open this session
		loadingFile = true;
		try {
			const content = await adminSchemas.getContent(s.name);
			buffers[mondrianKey(s.name)] = content;
			baselines[mondrianKey(s.name)] = content;
			void prefetchCubeDetail(content);
		} catch (e) {
			saveMsg = { tone: 'error', text: e instanceof Error ? e.message : 'Failed to load schema.' };
			selected = null;
		} finally {
			loadingFile = false;
		}
	}

	async function selectOssie(ds: AdminDatasource): Promise<void> {
		selected = { kind: 'ossie', datasource: ds };
		afterSelect(`?open=${encodeURIComponent(`ossie:${ds.name}`)}`);
		// Refresh the completion catalogue on EVERY select, including re-opening a file that
		// is already open this session — otherwise A → B → A left B's names in A's editor.
		clearOssieCatalogue();
		void prefetchOssieModel(ds.name);
		if (buffers[ossieKey(ds.name)] !== undefined) return; // already open this session
		loadingFile = true;
		try {
			const content = await getOssieSchemaYaml(ds.name);
			const yaml = content ?? ossieTemplate(ds.name);
			buffers[ossieKey(ds.name)] = yaml;
			if (content !== null) baselines[ossieKey(ds.name)] = content;
		} catch (e) {
			clearOssieCatalogue();
			saveMsg = { tone: 'error', text: e instanceof Error ? e.message : 'Failed to load model.' };
			selected = null;
		} finally {
			loadingFile = false;
		}
	}

	function afterSelect(url: string): void {
		saveMsg = null;
		cubeDiffResult = null;
		conflictPending = false;
		void goto(url, { replaceState: true, reset: false });
	}

	function startNewSchema(): void {
		const name = newName.trim();
		if (!name) return;
		if (schemas.some((s) => s.name === name)) {
			saveMsg = { tone: 'error', text: `A schema named "${name}" already exists.` };
			return;
		}
		const synthetic: AdminSchema = { name, path: `/datasources/${name}.xml`, type: 'MONDRIAN' };
		buffers[mondrianKey(name)] = NEW_SCHEMA_TEMPLATE;
		// No baseline: an unset baseline means "never saved", so the conflict check in
		// save() (which only fires once a baseline exists) is skipped for a brand-new file.
		selected = { kind: 'mondrian', schema: synthetic };
		newName = '';
		creating = false;
		saveMsg = null;
	}

	function onEditorChange(value: string): void {
		if (!selected || !bufferKey) return;
		buffers[bufferKey] = value;
	}

	/** Read the server's current copy of the open file, for the last-writer-wins conflict
	 *  check. Returns `undefined` when the read itself fails — the caller then proceeds
	 *  rather than blocking Save on a transient read failure. */
	async function serverCopy(): Promise<string | undefined> {
		if (!selected) return undefined;
		return selected.kind === 'mondrian'
			? await adminSchemas.getContent(selected.schema.name).catch(() => undefined)
			: ((await getOssieSchemaYaml(selected.datasource.name).catch(() => undefined)) ?? undefined);
	}

	async function save(): Promise<void> {
		if (!selected || saving) return;
		if (hasErrors) {
			saveMsg = { tone: 'error', text: 'Fix the validation errors below before saving.' };
			return;
		}
		const baseline = baselines[bufferKey];
		if (baseline !== undefined) {
			saving = true;
			try {
				const latestOnServer = await serverCopy();
				if (latestOnServer !== undefined && latestOnServer !== baseline) {
					conflictPending = true;
					return;
				}
			} catch {
				// Can't confirm — proceed rather than block Save on a transient read failure.
			} finally {
				saving = false;
			}
		}
		await doSave();
	}

	async function doSave(): Promise<void> {
		if (!selected) return;
		conflictPending = false;
		saving = true;
		saveMsg = null;
		try {
			if (selected.kind === 'mondrian') {
				await saveMondrian();
			} else {
				await saveOssie();
			}
		} catch (e) {
			saveMsg = { tone: 'error', text: e instanceof Error ? e.message : 'Save failed.' };
		} finally {
			saving = false;
		}
	}

	async function saveMondrian(): Promise<void> {
		if (selected?.kind !== 'mondrian') return;
		// Local snapshot: narrowing of the `$state` variable is lost across the awaits below.
		const schema = selected.schema;
		const name = schema.name;
		const xml = buffers[mondrianKey(name)] ?? '';
		await adminSchemas.upload(name, xml);
		baselines[mondrianKey(name)] = xml;
		if (!schemas.some((s) => s.name === name)) {
			schemas = [...schemas, { name, path: `/datasources/${name}.xml`, type: 'MONDRIAN' }];
		}

		const linked = datasources.filter((d) => d.schemaName === schema.path);
		const connectionNames = new Set(linked.map((d) => d.connectionName ?? d.name));
		let diffText = '';
		if (linked.length > 0) {
			const before = (await fetchCubeSummaries().catch(() => []))
				.filter((c) => connectionNames.has(c.connectionName))
				.map((c) => c.cubeName);
			await Promise.all(linked.map((d) => adminDatasources.refresh(d.name).catch(() => {})));
			const afterSummaries = await fetchCubeSummaries().catch(() => []);
			catalogue.setSummaries(afterSummaries);
			const after = afterSummaries
				.filter((c) => connectionNames.has(c.connectionName))
				.map((c) => c.cubeName);
			cubeDiffResult = diffCubeNames(before, after);
			const parts: string[] = [];
			if (cubeDiffResult.added.length) parts.push(`+${cubeDiffResult.added.length} cube(s)`);
			if (cubeDiffResult.removed.length) parts.push(`-${cubeDiffResult.removed.length} cube(s)`);
			diffText = parts.length ? ` (${parts.join(', ')})` : ' (no cube changes)';
		}

		saveMsg = {
			tone: 'success',
			text:
				linked.length > 0
					? `Saved "${name}" and refreshed ${linked.length} datasource(s)${diffText}.`
					: `Saved "${name}". No datasource is attached to this schema yet — attach one in Admin › Datasources to query it.`
		};
	}

	async function saveOssie(): Promise<void> {
		if (selected?.kind !== 'ossie') return;
		// Local snapshot: narrowing of the `$state` variable is lost across the awaits below.
		const ds = selected.datasource;
		const yaml = buffers[ossieKey(ds.name)] ?? '';
		// Snapshot the live dataset/metric names BEFORE the save so the post-refresh diff
		// answers "what changed". Only the snapshot fetched for THIS datasource counts —
		// a catalogue left over from another file (or from a failed prefetch) is not an
		// honest "before" — skip the diff rather than diff against the wrong model.
		const catalogueIsForDs = ossieCatalogueDs === ds.name;
		const before =
			catalogueIsForDs && ossieCatalogue.datasets.length > 0 ? [...ossieCatalogue.datasets] : null;
		const beforeMetrics =
			catalogueIsForDs && ossieCatalogue.metrics.length > 0 ? [...ossieCatalogue.metrics] : null;

		await saveOssieSchemaYaml(ds.name, yaml);
		baselines[ossieKey(ds.name)] = yaml;

		const refreshed = await adminDatasources.refresh(ds.name).catch(() => null);
		let diffText = '';
		if (refreshed !== null) {
			const model = await prefetchOssieModel(ds.name);
			if (model && before) {
				cubeDiffResult = diffCubeNames(
					before,
					model.datasets.map((d) => d.name)
				);
				const parts: string[] = [];
				if (cubeDiffResult.added.length) parts.push(`+${cubeDiffResult.added.length} dataset(s)`);
				if (cubeDiffResult.removed.length)
					parts.push(`-${cubeDiffResult.removed.length} dataset(s)`);
				if (beforeMetrics) {
					const metricDiff = diffCubeNames(
						beforeMetrics,
						model.metrics.map((m) => m.name)
					);
					if (metricDiff.added.length) parts.push(`+${metricDiff.added.length} metric(s)`);
					if (metricDiff.removed.length) parts.push(`-${metricDiff.removed.length} metric(s)`);
				}
				diffText = parts.length ? ` (${parts.join(', ')})` : ' (no dataset changes)';
			}
		}

		saveMsg = {
			tone: 'success',
			text:
				refreshed !== null
					? `Saved "${ds.name}" and refreshed its connection${diffText}.`
					: `Saved "${ds.name}". The connection could not be refreshed — use Admin › Datasources › Refresh to pick the model up.`
		};
	}
</script>

<div class="ide">
	<header class="ide__header">
		<h1><FileCode class="h-4 w-4" aria-hidden="true" /> Model IDE</h1>
		<div class="ide__header-actions">
			{#if selected}
				<span class="ide__status">
					{#if isDirty}Unsaved changes{:else}Saved{/if}
					{#if lintIssues.length > 0}
						· {lintIssues.filter((i) => i.severity === 'error').length} error(s), {lintIssues.filter(
							(i) => i.severity === 'warning'
						).length} warning(s)
					{/if}
				</span>
				<Button onclick={save} disabled={saving || !isDirty || hasErrors}>
					{saving ? 'Saving…' : 'Save'}
				</Button>
			{/if}
		</div>
	</header>

	{#if saveMsg}
		<div class="ide__banner">
			<FeedbackBanner tone={saveMsg.tone} size="sm">{saveMsg.text}</FeedbackBanner>
		</div>
	{/if}

	<div class="ide__body">
		<aside class="ide__tree">
			{#if loadingList}
				<Skeleton rows={5} />
			{:else}
				<p class="ide__tree-heading">Mondrian schemas</p>
				<ul class="ide__file-list">
					{#each schemas as s (s.name)}
						{@const dirty =
							buffers[mondrianKey(s.name)] !== undefined &&
							buffers[mondrianKey(s.name)] !== baselines[mondrianKey(s.name)]}
						<li>
							<button
								type="button"
								class="ide__file"
								class:ide__file--active={selected?.kind === 'mondrian' &&
									selected.schema.name === s.name}
								onclick={() => selectSchema(s)}
							>
								<span class="ide__file-dot" class:ide__file-dot--dirty={dirty} aria-hidden="true"
								></span>
								{s.name}
							</button>
						</li>
					{/each}
				</ul>
				<p class="ide__tree-heading">Ossie models</p>
				<ul class="ide__file-list">
					{#each ossieDatasources as ds (ds.name)}
						{@const dirty =
							buffers[ossieKey(ds.name)] !== undefined &&
							buffers[ossieKey(ds.name)] !== baselines[ossieKey(ds.name)]}
						<li>
							<button
								type="button"
								class="ide__file"
								class:ide__file--active={selected?.kind === 'ossie' &&
									selected.datasource.name === ds.name}
								onclick={() => selectOssie(ds)}
							>
								<span class="ide__file-dot" class:ide__file-dot--dirty={dirty} aria-hidden="true"
								></span>
								{ds.name}
							</button>
						</li>
					{/each}
					{#if ossieDatasources.length === 0}
						<li class="ide__tree-empty">No OSSIE datasources</li>
					{/if}
				</ul>
				{#if creating}
					<div class="ide__new-file">
						<input
							type="text"
							bind:value={newName}
							placeholder="NewSchemaName"
							aria-label="New schema name"
							onkeydown={(e) => e.key === 'Enter' && startNewSchema()}
						/>
						<Button size="sm" onclick={startNewSchema}>Create</Button>
						<Button size="sm" variant="ghost" onclick={() => (creating = false)}>Cancel</Button>
					</div>
				{:else}
					<Button variant="outline" size="sm" onclick={() => (creating = true)}>
						<Plus class="h-3.5 w-3.5" aria-hidden="true" /> New schema
					</Button>
				{/if}
			{/if}
		</aside>

		<section class="ide__editor">
			{#if !selected}
				<EmptyState
					icon={FileCode}
					title="No schema open"
					description="Pick a Mondrian schema or an Ossie model from the list to start editing."
					compact
				/>
			{:else if loadingFile}
				<Skeleton rows={8} />
			{:else if monacoLanguageReady}
				<MonacoEditor
					value={currentContent}
					language={selected.kind === 'ossie' ? 'yaml' : 'mondrian-xml'}
					path={`inmemory://model-ide/${selected.kind}/${encodeURIComponent(selectedName ?? '')}.${selected.kind === 'ossie' ? 'yaml' : 'xml'}`}
					{markers}
					minHeight="calc(100vh - 220px)"
					onChange={onEditorChange}
				/>
				{#if selected.kind === 'ossie'}
					<p class="ide__linked">
						Ossie model of datasource {selected.datasource.name} — validated on save by the server; the
						connection is refreshed afterwards.
					</p>
				{:else if linkedDatasources.length > 0}
					<p class="ide__linked">
						Attached to: {linkedDatasources.map((d) => d.name).join(', ')}
					</p>
				{/if}
				{#if cubeDiffResult && (cubeDiffResult.added.length || cubeDiffResult.removed.length)}
					<div class="ide__diff">
						{#if cubeDiffResult.added.length}
							<span class="ide__diff-added">+ {cubeDiffResult.added.join(', ')}</span>
						{/if}
						{#if cubeDiffResult.removed.length}
							<span class="ide__diff-removed">- {cubeDiffResult.removed.join(', ')}</span>
						{/if}
					</div>
				{/if}
				{#if lintIssues.length > 0}
					<ul class="ide__problems" aria-label="Problems">
						{#each lintIssues as issue, i (i)}
							<li class="ide__problem ide__problem--{issue.severity}">
								<span class="ide__problem-pos">{issue.startLine}:{issue.startColumn}</span>
								{issue.message}
							</li>
						{/each}
					</ul>
				{/if}
			{/if}
		</section>
	</div>
</div>

<ConfirmModal
	title="Model has been changed by another editor"
	message="This file was saved by someone else since you opened it. Save anyway and overwrite their changes?"
	confirmLabel="Overwrite"
	variant="danger"
	open={conflictPending}
	onConfirm={doSave}
	onCancel={() => (conflictPending = false)}
/>

<style>
	.ide {
		display: flex;
		flex-direction: column;
		min-height: 0;
		flex: 1;
		overflow: hidden;
	}
	.ide__header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
		padding: 0.5rem 0.75rem;
		border-bottom: 1px solid var(--border);
	}
	.ide__header h1 {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		margin: 0;
		font-size: var(--fs-sm);
		font-weight: 600;
	}
	.ide__header-actions {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}
	.ide__status {
		font-size: var(--fs-xs);
		color: var(--muted-foreground);
	}
	.ide__banner {
		padding: 0.5rem 0.75rem 0;
	}
	.ide__body {
		display: flex;
		flex: 1;
		min-height: 0;
		overflow: hidden;
	}
	.ide__tree {
		width: 220px;
		flex-shrink: 0;
		border-right: 1px solid var(--border);
		padding: 0.5rem;
		overflow-y: auto;
	}
	.ide__tree-heading {
		margin: 0 0 0.25rem;
		font-size: var(--fs-xs);
		font-weight: 600;
		color: var(--muted-foreground);
		text-transform: uppercase;
		letter-spacing: 0.04em;
	}
	.ide__tree-empty {
		font-size: var(--fs-xs);
		color: var(--muted-foreground);
		padding: 0.2rem 0.4rem;
	}
	.ide__file-list {
		list-style: none;
		margin: 0 0 0.75rem;
		padding: 0;
	}
	.ide__file {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		width: 100%;
		text-align: left;
		padding: 0.3rem 0.4rem;
		border-radius: 0.25rem;
		font-size: var(--fs-sm);
		background: none;
		border: none;
		cursor: pointer;
		color: var(--foreground);
	}
	.ide__file:hover {
		background: var(--accent);
	}
	.ide__file--active {
		background: var(--accent);
		font-weight: 600;
	}
	.ide__file-dot {
		width: 6px;
		height: 6px;
		border-radius: 999px;
		background: transparent;
		flex-shrink: 0;
	}
	.ide__file-dot--dirty {
		background: var(--primary);
	}
	.ide__new-file {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
	}
	.ide__new-file input {
		border: 1px solid var(--border);
		border-radius: 0.25rem;
		padding: 0.3rem 0.4rem;
		font-size: var(--fs-sm);
		background: var(--background);
		color: var(--foreground);
	}
	.ide__editor {
		flex: 1;
		min-width: 0;
		padding: 0.75rem;
		overflow-y: auto;
	}
	.ide__linked {
		margin: 0.5rem 0 0;
		font-size: var(--fs-xs);
		color: var(--muted-foreground);
	}
	.ide__diff {
		display: flex;
		gap: 0.75rem;
		margin-top: 0.35rem;
		font-size: var(--fs-xs);
		font-family: var(--font-mono);
	}
	.ide__diff-added {
		color: var(--success, #16a34a);
	}
	.ide__diff-removed {
		color: var(--destructive);
	}
	.ide__problems {
		list-style: none;
		margin: 0.75rem 0 0;
		padding: 0;
		border-top: 1px solid var(--border);
	}
	.ide__problem {
		display: flex;
		gap: 0.5rem;
		padding: 0.25rem 0.1rem;
		font-size: var(--fs-xs);
		font-family: var(--font-mono);
	}
	.ide__problem--error {
		color: var(--destructive);
	}
	.ide__problem--warning {
		color: var(--warning, #b45309);
	}
	.ide__problem-pos {
		color: var(--muted-foreground);
		flex-shrink: 0;
	}
</style>

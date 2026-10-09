<!--
  Admin › Model IDE (saiku#1428) — a Monaco-based editor for Mondrian 4 schema XML with
  schema-aware autocomplete (fed by the live `/ai/cubes` catalogue), an in-browser
  structural lint, and a Save flow that refreshes the attached datasource(s) and shows
  what changed in their cube list — all without a page reload.

  Scope note (see the PR description for the full breakdown): this covers Mondrian XML
  only. Ossie YAML editing, a git-branch preview/merge flow, and deprecating the older
  Admin › Schemas upload form are follow-up work — the backend support they need (a raw
  Ossie YAML read/write endpoint; a temporary saiku-home overlay + discover-refresh-against-
  it + diff) doesn't exist yet, and this repo's external Maven dependencies (the Mondrian
  and Ossie forks published on GitHub Packages) weren't resolvable from this sandbox, so
  backend changes here would have shipped unbuilt and unverified. Everything in this PR is
  saiku-ui only, against REST endpoints that already exist.
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
	import { fetchCubeSummaries, fetchCubeDetail } from '$lib/api/aiCubes';
	import { MondrianCatalogue, extractCubeNames } from '$lib/monaco/mondrian-catalogue';
	import { lintMondrianXml, type LintIssue } from '$lib/monaco/mondrian-xml-lint';
	import { diffCubeNames, type CubeDiff } from './cubeDiff';
	import { FileCode, Plus } from '@lucide/svelte';

	const NEW_SCHEMA_TEMPLATE = `<Schema name="NewSchema">\n  <Cube name="NewCube">\n    <Table name="table_name"/>\n    <Dimension name="Dimension1" foreignKey="fk_column">\n      <Hierarchy hasAll="true" primaryKey="pk_column">\n        <Level name="Level1" column="column_name" uniqueMembers="true"/>\n      </Hierarchy>\n    </Dimension>\n    <Measure name="Measure1" column="amount_column" aggregator="sum"/>\n  </Cube>\n</Schema>\n`;

	// monaco.MarkerSeverity.Error / .Warning — hardcoded rather than importing `monaco-editor`
	// here for its enum, which would pull the package into this file's eager import graph and
	// crash SvelteKit's SSR pass the same way it crashes Vitest's `node` environment (no
	// `window`); see MonacoEditor.svelte's own dynamic `import('monaco-editor')` for why.
	const MARKER_SEVERITY = { error: 8, warning: 4 } as const;

	let schemas = $state<AdminSchema[]>([]);
	let datasources = $state<AdminDatasource[]>([]);
	let selected = $state<AdminSchema | null>(null);
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

	const currentContent = $derived(selected ? (buffers[selected.name] ?? '') : '');
	const lintIssues = $derived<LintIssue[]>(currentContent ? lintMondrianXml(currentContent) : []);
	const hasErrors = $derived(lintIssues.some((i) => i.severity === 'error'));
	const isDirty = $derived(
		selected !== null && currentContent !== (baselines[selected.name] ?? currentContent)
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
	/** Datasources whose attached schema is the file currently open. */
	const linkedDatasources = $derived(
		selected ? datasources.filter((d) => d.schemaName === selected!.path) : []
	);

	onMount(async () => {
		const [{ registerMondrianXmlLanguage }] = await Promise.all([
			import('$lib/monaco/mondrian-xml-lang'),
			loadAll()
		]);
		registerMondrianXmlLanguage(() => catalogue);
		monacoLanguageReady = true;

		const openParam = page.url.searchParams.get('open');
		if (openParam) {
			const match = schemas.find((s) => s.name === openParam);
			if (match) await selectSchema(match);
		}
	});

	async function loadAll(): Promise<void> {
		loadingList = true;
		try {
			const [schemaList, datasourceList, cubeSummaries] = await Promise.all([
				adminSchemas.list(),
				adminDatasources.list(),
				fetchCubeSummaries().catch(() => [])
			]);
			schemas = schemaList;
			datasources = datasourceList;
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

	async function selectSchema(s: AdminSchema): Promise<void> {
		selected = s;
		saveMsg = null;
		cubeDiffResult = null;
		void goto(`?open=${encodeURIComponent(s.name)}`, {
			replaceState: true,
			noScroll: true,
			keepFocus: true
		});
		if (buffers[s.name] !== undefined) return; // already open this session
		loadingFile = true;
		try {
			const content = await adminSchemas.getContent(s.name);
			buffers[s.name] = content;
			baselines[s.name] = content;
			void prefetchCubeDetail(content);
		} catch (e) {
			saveMsg = { tone: 'error', text: e instanceof Error ? e.message : 'Failed to load schema.' };
			selected = null;
		} finally {
			loadingFile = false;
		}
	}

	function startNewSchema(): void {
		const name = newName.trim();
		if (!name) return;
		if (schemas.some((s) => s.name === name)) {
			saveMsg = { tone: 'error', text: `A schema named "${name}" already exists.` };
			return;
		}
		const synthetic: AdminSchema = { name, path: `/datasources/${name}.xml`, type: 'MONDRIAN' };
		buffers[name] = NEW_SCHEMA_TEMPLATE;
		// No baseline: an unset baseline means "never saved", so the conflict check in
		// doSave() (which only fires once a baseline exists) is skipped for a brand-new file.
		selected = synthetic;
		newName = '';
		creating = false;
		saveMsg = null;
	}

	function onEditorChange(value: string): void {
		if (!selected) return;
		buffers[selected.name] = value;
	}

	async function save(): Promise<void> {
		if (!selected || saving) return;
		if (hasErrors) {
			saveMsg = { tone: 'error', text: 'Fix the validation errors below before saving.' };
			return;
		}
		const baseline = baselines[selected.name];
		if (baseline !== undefined) {
			saving = true;
			try {
				const latestOnServer = await adminSchemas.getContent(selected.name);
				if (latestOnServer !== baseline) {
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
		const name = selected.name;
		const xml = buffers[name] ?? '';
		try {
			await adminSchemas.upload(name, xml);
			baselines[name] = xml;
			if (!schemas.some((s) => s.name === name)) {
				schemas = [...schemas, { name, path: `/datasources/${name}.xml`, type: 'MONDRIAN' }];
			}

			const linked = datasources.filter(
				(d) => d.schemaName === (selected?.path ?? `/datasources/${name}.xml`)
			);
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
		} catch (e) {
			saveMsg = { tone: 'error', text: e instanceof Error ? e.message : 'Save failed.' };
		} finally {
			saving = false;
		}
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
				<ul class="ide__file-list">
					{#each schemas as s (s.name)}
						{@const dirty = buffers[s.name] !== undefined && buffers[s.name] !== baselines[s.name]}
						<li>
							<button
								type="button"
								class="ide__file"
								class:ide__file--active={selected?.name === s.name}
								onclick={() => selectSchema(s)}
							>
								<span class="ide__file-dot" class:ide__file-dot--dirty={dirty} aria-hidden="true"
								></span>
								{s.name}
							</button>
						</li>
					{/each}
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
					description="Pick a schema from the list, or create a new one, to start editing."
					compact
				/>
			{:else if loadingFile}
				<Skeleton rows={8} />
			{:else if monacoLanguageReady}
				<MonacoEditor
					value={currentContent}
					language="mondrian-xml"
					path={`inmemory://model-ide/${encodeURIComponent(selected.name)}.mondrian-schema.xml`}
					{markers}
					minHeight="calc(100vh - 220px)"
					onChange={onEditorChange}
				/>
				{#if linkedDatasources.length > 0}
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
	message="This schema was saved by someone else since you opened it. Save anyway and overwrite their changes?"
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
	.ide__file-list {
		list-style: none;
		margin: 0 0 0.5rem;
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

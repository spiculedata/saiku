<!--
  saiku#1435 — <saiku-embed kind="creator"> Creator Mode.

  A deliberately STRIPPED workbench: a catalogue of the ONE cube the embed
  token pins, a rows picker, a measures picker, Run / Save, and the visitor's
  own saved objects. There is no cube switcher, no repository tree, no MDX
  editor, no datasource admin — the surface is small because the server-side
  scope is small (one cube, one tenant, one folder).

  Theming: everything below reads the --saiku-embed-* variables the host page
  already sets, plus three new ones for the builder chrome
  (--saiku-embed-surface, --saiku-embed-control, --saiku-embed-accent-soft).
-->
<script lang="ts">
	/*
	 * Creator Mode (saiku#1435). The strip-down of the workspace that OEM/ISV
	 * embeds need: an end user of the HOST product builds their own dashboard
	 * against a pinned cube without ever leaving the host page.
	 *
	 * Security posture, restated for the client side: this component never sends
	 * a repository path. It sends a cube reference (which the server pins
	 * against the token) and an object NAME (which the server sanitises into the
	 * tenant's folder). There is no UI path that could name another tenant, a
	 * different cube, or a file outside the folder, because the endpoints
	 * themselves refuse all three.
	 */
	import EmbedTable from './EmbedTable.svelte';
	import EmbedChart from './EmbedChart.svelte';
	import {
		creatorErrorMessage,
		deleteAuthoringObject,
		fetchAuthoringContext,
		fetchAuthoringObjects,
		previewAuthoringQuery,
		saveAuthoringDashboard,
		saveAuthoringQuery,
		type CreatorAxisPick,
		type CreatorSelection,
		type EmbedCreatorContext,
		type EmbedCreatorObject,
		type EmbedCreatorLevel
	} from './creatorApi';
	import type { EmbedRow } from './types';

	interface Props {
		server?: string;
		token?: string;
		/** Cube reference: connection/catalog/schema/cube. */
		cube?: string;
		/** Emit upward so the host element can re-dispatch saiku:load / saiku:error. */
		onLoad?: (detail: Record<string, unknown>) => void;
		onError?: (detail: { message: string }) => void;
	}

	let { server = '', token = '', cube = '', onLoad, onError }: Props = $props();

	let context = $state<EmbedCreatorContext | null>(null);
	let rows = $state<CreatorAxisPick[]>([]);
	let measures = $state<string[]>([]);
	let result = $state<EmbedRow[] | null>(null);
	let objects = $state<EmbedCreatorObject[]>([]);
	let loading = $state(false);
	let saving = $state(false);
	let error = $state<string | null>(null);
	let notice = $state<string | null>(null);
	let render = $state<'table' | 'chart'>('table');
	let chartMode = $state('bar');
	let name = $state('');

	const selection = $derived<CreatorSelection>({ rows, measures });

	/** Every level across the pinned cube's dimensions, flattened for the picker. */
	const levels = $derived(
		(context?.dimensions ?? []).flatMap((d) =>
			d.levels.map((l) => ({ ...l, dimension: d.name, dimensionCaption: d.caption }))
		)
	);

	$effect(() => {
		const s = server.trim();
		const c = cube.trim();
		const t = token.trim();
		if (!c) {
			error = 'Configure the embed: a cube reference is required.';
			return;
		}
		let cancelled = false;
		loading = true;
		error = null;
		Promise.all([
			fetchAuthoringContext(s, c, t || undefined),
			fetchAuthoringObjects(s, c, t || undefined)
		])
			.then(([ctx, objs]) => {
				if (cancelled) return;
				context = ctx;
				objects = objs.objects ?? [];
				// Start from the first visible level + first measure so the surface
				// is immediately runnable; the visitor refines from there.
				const first = levelsFrom(ctx)[0];
				if (first) rows = [{ level: first.name, members: [] }];
				if (ctx.measures.length > 0) measures = [ctx.measures[0].name];
				onLoad?.({ kind: 'creator', cube: ctx.cube, tenant: ctx.tenantId });
			})
			.catch((e: unknown) => {
				if (cancelled) return;
				error = creatorErrorMessage(e);
				onError?.({ message: error });
			})
			.finally(() => {
				if (!cancelled) loading = false;
			});
		return () => {
			cancelled = true;
		};
	});

	function levelsFrom(ctx: EmbedCreatorContext): EmbedCreatorLevel[] {
		return ctx.dimensions.flatMap((d) => d.levels);
	}

	function levelFor(ctx: EmbedCreatorContext, name: string): EmbedCreatorLevel | undefined {
		for (const d of ctx.dimensions) {
			for (const l of d.levels) {
				if (l.name === name) return l;
			}
		}
		return undefined;
	}

	function addLevel(level: string): void {
		if (!level) return;
		rows = [...rows, { level, members: [] }];
	}

	function removeLevel(index: number): void {
		rows = rows.filter((_, i) => i !== index);
	}

	function toggleMeasure(m: string): void {
		measures = measures.includes(m) ? measures.filter((x) => x !== m) : [...measures, m];
	}

	/** Narrow one level to a single member (the common "one store" filter). An
	 *  empty member list means "all members", which is the default. */
	function setMember(index: number, member: string): void {
		rows = rows.map((r, i) => (i === index ? { ...r, members: member ? [member] : [] } : r));
	}

	function fail(e: unknown): void {
		const message = creatorErrorMessage(e);
		error = message;
		notice = null;
		onError?.({ message });
	}

	async function run(): Promise<void> {
		loading = true;
		error = null;
		notice = null;
		try {
			const resp = await previewAuthoringQuery(server, cube, selection, token || undefined);
			result = resp.data ?? [];
			onLoad?.({ kind: 'creator-preview', rows: result.length });
		} catch (e) {
			fail(e);
		} finally {
			loading = false;
		}
	}

	function requireName(): string | null {
		const trimmed = name.trim();
		if (!trimmed) {
			error = 'Give your dashboard a name before saving.';
			return null;
		}
		return trimmed;
	}

	async function saveQuery(): Promise<void> {
		const label = requireName();
		if (!label) return;
		saving = true;
		error = null;
		try {
			const saved = await saveAuthoringQuery(server, cube, label, selection, token || undefined);
			notice = `Saved ${saved.path}`;
			await refreshObjects();
		} catch (e) {
			fail(e);
		} finally {
			saving = false;
		}
	}

	async function saveDashboard(): Promise<void> {
		const label = requireName();
		if (!label) return;
		saving = true;
		error = null;
		try {
			// The dashboard tile references a saved query, so save that first —
			// the server stores the pair, not a self-contained query blob.
			const query = await saveAuthoringQuery(
				server,
				cube,
				`${label} (query)`,
				selection,
				token || undefined
			);
			const dash = await saveAuthoringDashboard(
				server,
				cube,
				label,
				selection,
				query.path,
				chartMode,
				token || undefined
			);
			notice = `Saved ${dash.path}`;
			await refreshObjects();
		} catch (e) {
			fail(e);
		} finally {
			saving = false;
		}
	}

	async function refreshObjects(): Promise<void> {
		try {
			const objs = await fetchAuthoringObjects(server, cube, token || undefined);
			objects = objs.objects ?? [];
		} catch (e) {
			// A failed refresh must not lose the just-saved object silently, so
			// surface it, but don't blow away the current editor state.
			fail(e);
		}
	}

	async function remove(nameToDelete: string): Promise<void> {
		saving = true;
		error = null;
		try {
			await deleteAuthoringObject(server, cube, nameToDelete, token || undefined);
			notice = `Deleted ${nameToDelete}`;
			await refreshObjects();
		} catch (e) {
			fail(e);
		} finally {
			saving = false;
		}
	}
</script>

<div class="creator">
	<header>
		<h3>{context?.cubeCaption ?? 'Analytics'}</h3>
		{#if context}
			<span class="scope" title={context.scopePath}>saved to your workspace</span>
		{/if}
	</header>

	{#if loading && !context}
		<p class="muted">Loading…</p>
	{:else if error && !context}
		<p class="error" role="alert">{error}</p>
	{:else if context}
		<section class="builder">
			<fieldset>
				<legend>Rows</legend>
				{#each rows as pick, i (i)}
					<div class="pick">
						<label>
							<span class="sr">{`Level ${i + 1}`}</span>
							<select
								class="control"
								value={pick.level}
								onchange={(e) => {
									const next = e.currentTarget.value;
									rows = rows.map((r, j) => (j === i ? { ...r, level: next, members: [] } : r));
								}}
							>
								{#each levels as l (l.name)}
									<option value={l.name} selected={l.name === pick.level}>
										{l.caption ?? l.name}
									</option>
								{/each}
							</select>
						</label>
						<label>
							<span class="sr">{`Member filter for level ${i + 1}`}</span>
							<select
								class="control"
								value={pick.members[0] ?? ''}
								onchange={(e) => setMember(i, e.currentTarget.value)}
							>
								<option value="">All members</option>
								{#each levelFor(context, pick.level)?.members ?? [] as m (m)}
									<option value={m}>{m}</option>
								{/each}
							</select>
						</label>
						<button type="button" class="link" onclick={() => removeLevel(i)}>Remove</button>
					</div>
				{/each}
				<label>
					<span class="sr">Add a level</span>
					<select
						class="control"
						value=""
						onchange={(e) => {
							addLevel(e.currentTarget.value);
							e.currentTarget.value = '';
						}}
					>
						<option value="" disabled>Add a level…</option>
						{#each levels as l (l.name)}
							<option value={l.name}>{l.caption ?? l.name}</option>
						{/each}
					</select>
				</label>
			</fieldset>

			<fieldset>
				<legend>Measures</legend>
				<div class="measures">
					{#each context.measures as m (m.name)}
						<label class="check">
							<input
								type="checkbox"
								checked={measures.includes(m.name)}
								onchange={() => toggleMeasure(m.name)}
							/>
							{m.caption ?? m.name}
						</label>
					{/each}
				</div>
			</fieldset>

			<div class="actions">
				<button type="button" onclick={run} disabled={loading || measures.length === 0}>Run</button>
				<label class="inline">
					Show as
					<select class="control" bind:value={render}>
						<option value="table">Table</option>
						<option value="chart">Chart</option>
					</select>
				</label>
				{#if render === 'chart'}
					<label class="inline">
						Chart
						<select class="control" bind:value={chartMode}>
							<option value="bar">Bar</option>
							<option value="line">Line</option>
							<option value="pie">Pie</option>
						</select>
					</label>
				{/if}
			</div>
		</section>

		<section class="preview">
			{#if loading}
				<p class="muted">Running…</p>
			{:else if render === 'chart' && result}
				<EmbedChart rows={result} mode={chartMode} />
			{:else if result}
				<EmbedTable rows={result} />
			{/if}
		</section>

		<section class="save">
			<label class="inline">
				Name
				<input
					class="control"
					type="text"
					bind:value={name}
					placeholder="My dashboard"
					maxlength="64"
				/>
			</label>
			<button type="button" onclick={saveQuery} disabled={saving || measures.length === 0}>
				Save query
			</button>
			<button type="button" onclick={saveDashboard} disabled={saving || measures.length === 0}>
				Save dashboard
			</button>
		</section>

		{#if notice}
			<p class="notice" role="status">{notice}</p>
		{/if}
		{#if error}
			<p class="error" role="alert">{error}</p>
		{/if}

		{#if objects.length > 0}
			<section class="objects">
				<h4>Your saved items</h4>
				<ul>
					{#each objects as o (o.path)}
						<li>
							<span class="badge">{o.type}</span>
							<span class="name">{o.name}</span>
							<button type="button" class="link" onclick={() => remove(o.name)}>Delete</button>
						</li>
					{/each}
				</ul>
			</section>
		{/if}
	{/if}
</div>

<style>
	/* Shadow-DOM scoped. --saiku-embed-surface / --saiku-embed-control are new
     * in #1435: the read surface never needed a panel background or a control
     * border, the builder chrome does. Both default to the existing tokens so a
     * host that only sets the pre-#1435 variables still looks right. */
	.creator {
		display: flex;
		flex-direction: column;
		gap: 12px;
		font-family: system-ui, sans-serif;
		font-size: 13px;
		background: var(--saiku-embed-surface, transparent);
		color: inherit;
	}
	header {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 8px;
	}
	h3 {
		margin: 0;
		font-size: 15px;
		font-weight: 600;
	}
	h4 {
		margin: 0 0 6px;
		font-size: 13px;
		font-weight: 600;
	}
	.scope {
		color: var(--saiku-embed-muted, #6b7280);
		font-size: 12px;
	}
	fieldset {
		border: 1px solid var(--saiku-embed-border, #e5e7eb);
		border-radius: 6px;
		padding: 8px 10px;
		margin: 0;
	}
	legend {
		font-size: 12px;
		color: var(--saiku-embed-muted, #6b7280);
	}
	.pick {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
		align-items: center;
		margin-bottom: 6px;
	}
	.measures {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
	}
	.check {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	.actions,
	.save {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		align-items: center;
	}
	.inline {
		display: inline-flex;
		align-items: center;
		gap: 4px;
	}
	select,
	input[type='text'] {
		background: var(--saiku-embed-control, transparent);
		color: inherit;
		border: 1px solid var(--saiku-embed-border, #e5e7eb);
		border-radius: 4px;
		padding: 3px 6px;
		font: inherit;
	}
	.control:hover {
		background: var(--saiku-embed-accent-soft, transparent);
		border-color: var(--saiku-embed-accent, #4f46e5);
	}
	button {
		background: var(--saiku-embed-accent, #4f46e5);
		color: #fff;
		border: 0;
		border-radius: 4px;
		padding: 5px 12px;
		font: inherit;
		cursor: pointer;
	}
	button:disabled {
		opacity: 0.6;
		cursor: not-allowed;
	}
	button:not(:disabled):hover {
		background: var(--saiku-embed-accent-soft, var(--saiku-embed-accent, #4f46e5));
	}
	button.link {
		background: none;
		color: var(--saiku-embed-muted, #6b7280);
		padding: 0;
		text-decoration: underline;
	}
	.muted {
		color: var(--saiku-embed-muted, #6b7280);
		margin: 0;
	}
	.error {
		color: var(--saiku-embed-error, #b91c1c);
		margin: 0;
	}
	.notice {
		color: var(--saiku-embed-positive, #047857);
		margin: 0;
	}
	.objects ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.objects li {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.badge {
		font-size: 11px;
		border: 1px solid var(--saiku-embed-border, #e5e7eb);
		border-radius: 999px;
		padding: 0 6px;
		color: var(--saiku-embed-muted, #6b7280);
	}
	.name {
		flex: 1;
	}
	/* Visually hidden but screen-reader available — every control is labelled
     * by its context, not by floating helper text. */
	.sr {
		position: absolute;
		width: 1px;
		height: 1px;
		padding: 0;
		margin: -1px;
		overflow: hidden;
		clip: rect(0, 0, 0, 0);
		white-space: nowrap;
		border: 0;
	}
</style>

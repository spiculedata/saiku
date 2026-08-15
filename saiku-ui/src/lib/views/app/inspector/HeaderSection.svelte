<script lang="ts">
	/* Inspector → Header. The branded top bar: app name + accent word, eyebrow,
	 * context pill (label/value), and live badge. Writes via appDoc. */
	import { appDoc } from '$lib/stores/appDoc.svelte';

	import { Trash2 } from '@lucide/svelte';
	import { ALL_MEMBER, MAX_LEVEL_OPTIONS, isLevelSourced } from '$lib/views/app/contextPill';
	import { fetchLevelMembers } from '$lib/views/app/levelMembers';
	import { firstAppCube } from '$lib/views/app/appShell';
	import type { AppContextPill, AppContextPillOption } from '$lib/api/apps';

	const app = $derived(appDoc.current);
	const header = $derived(app?.header ?? {});
	const pill = $derived<AppContextPill>(header.contextPill ?? { label: '', value: '' });
	const pillOptions = $derived<AppContextPillOption[]>(pill.options ?? []);
	const pillFilter = $derived(pill.filter ?? { dimension: '', hierarchy: '', level: '' });

	const setHeader = (patch: Record<string, unknown>) => appDoc.updateHeader(patch);
	const val = (e: Event) => (e.currentTarget as HTMLInputElement).value;
	/** Empty string → drop the field (so an empty box means "off"). */
	const opt = (v: string) => (v.trim() === '' ? undefined : v);

	/** Immutable patch of the pill — every edit writes a whole new pill object. */
	const setPill = (patch: Partial<AppContextPill>) =>
		setHeader({ contextPill: { ...pill, ...patch } });

	function setOption(index: number, patch: Partial<AppContextPillOption>): void {
		setPill({ options: pillOptions.map((o, i) => (i === index ? { ...o, ...patch } : o)) });
	}
	function addOption(): void {
		setPill({ options: [...pillOptions, { label: '' }] });
	}
	function removeOption(index: number): void {
		const next = pillOptions.filter((_, i) => i !== index);
		setPill({ options: next.length > 0 ? next : undefined });
	}
	const levelSourced = $derived(pill.optionsSource === 'level');

	/* How many members the bound level actually has — shown so the author can
	 * see the list is real, and warned when it exceeds what a dropdown can
	 * usefully offer. Null until a complete binding exists. The fetch is the same
	 * cached one the shell uses, so opening the inspector costs no extra request. */
	let memberCount = $state<number | null>(null);
	/** saiku#1762: the pill has no cube of its own — it resolves against the
	 *  app's first tile cube. On an app with no tiles yet there is nothing to
	 *  read, and the bare "0 options loaded from the cube." read as a broken
	 *  level name rather than an empty app. */
	const hasCube = $derived(!!app && !!firstAppCube(app));
	$effect(() => {
		const a = app;
		if (!a || !isLevelSourced(pill)) {
			memberCount = null;
			return;
		}
		const f = pill.filter!;
		let cancelled = false;
		void fetchLevelMembers(firstAppCube(a), f.dimension, f.hierarchy, f.level).then((m) => {
			if (!cancelled) memberCount = m.length;
		});
		return () => {
			cancelled = true;
		};
	});

	/** Drop the binding entirely once every part is blank, so a half-typed
	 *  target never reaches the filter store. */
	function setFilterPart(part: 'dimension' | 'hierarchy' | 'level', value: string): void {
		const next = { ...pillFilter, [part]: value };
		const empty = !next.dimension.trim() && !next.hierarchy.trim() && !next.level.trim();
		setPill({ filter: empty ? undefined : next });
	}
</script>

<div class="insp-section">
	<div class="insp-label">Wordmark</div>
	<label class="insp-row"
		><span>App name</span>
		<input class="insp-input" value={app?.name ?? ''} oninput={(e) => appDoc.rename(val(e))} />
	</label>
	<label class="insp-row"
		><span>Accent word</span>
		<input
			class="insp-input"
			placeholder="e.g. Mart"
			value={header.wordmarkAccent ?? ''}
			oninput={(e) => setHeader({ wordmarkAccent: opt(val(e)) })}
		/>
	</label>
	<p class="insp-hint">
		The part of the name shown in the brand-mark colour (from Theme). Leave blank for a single-tone
		wordmark.
	</p>
	<label class="insp-row"
		><span>Eyebrow</span>
		<input
			class="insp-input"
			placeholder="e.g. Store Intelligence"
			value={header.eyebrow ?? ''}
			oninput={(e) => setHeader({ eyebrow: opt(val(e)) })}
		/>
	</label>
</div>

<div class="insp-section">
	<div class="insp-label">Context pill</div>
	<label class="insp-row"
		><span>Label</span>
		<input
			class="insp-input"
			placeholder="e.g. Store"
			value={pill.label}
			oninput={(e) => setPill({ label: val(e) })}
		/>
	</label>
	<label class="insp-row"
		><span>Default value</span>
		<input
			class="insp-input"
			placeholder="e.g. Portland #14"
			value={pill.value}
			oninput={(e) => setPill({ value: val(e) })}
		/>
	</label>

	<div class="insp-label">Options</div>
	<div class="insp-row">
		<span>Source</span>
		<div class="insp-seg">
			<button
				type="button"
				class:is-active={!levelSourced}
				onclick={() => setPill({ optionsSource: 'list' })}>Typed list</button
			>
			<button
				type="button"
				class:is-active={levelSourced}
				onclick={() => setPill({ optionsSource: 'level' })}>From cube</button
			>
		</div>
	</div>

	{#if levelSourced}
		<p class="insp-hint">
			Every member of the level below, read from the cube. A typed list goes stale the moment
			something is added or renamed — the cube always knows.
		</p>
		<label class="insp-row insp-toggle"
			><span>Add an "all" entry</span>
			<input
				type="checkbox"
				checked={pill.includeAll ?? false}
				onchange={(e) => setPill({ includeAll: (e.currentTarget as HTMLInputElement).checked })}
			/>
		</label>
		{#if pill.includeAll}
			<label class="insp-row"
				><span>"All" wording</span>
				<input
					class="insp-input"
					placeholder="All"
					value={pill.allLabel ?? ''}
					oninput={(e) => setPill({ allLabel: opt(val(e)) })}
				/>
			</label>
		{/if}
		<p class="insp-hint" class:ctx-warn={(memberCount ?? 0) > MAX_LEVEL_OPTIONS}>
			{#if memberCount === null}
				Fill in the level below to load the options.
			{:else if !hasCube}
				No cube in this app yet — add a tile bound to a cube and the options will load.
			{:else if memberCount > MAX_LEVEL_OPTIONS}
				{memberCount} members — only the first {MAX_LEVEL_OPTIONS} will be offered. A dropdown is the
				wrong control for a level this large.
			{:else}
				{memberCount} option{memberCount === 1 ? '' : 's'} loaded from the cube.
			{/if}
		</p>
	{:else}
		<p class="insp-hint">
			Add options to turn the pill into a selector. Leave "member" blank to match by the caption you
			typed; use <code>{ALL_MEMBER}</code> for an "all" entry that clears the filter.
		</p>
		<div class="insp-list">
			{#each pillOptions as o, i (i)}
				<div class="insp-list-item ctx-opt">
					<input
						class="insp-input"
						placeholder="Label, e.g. Seattle #3"
						value={o.label}
						oninput={(e) => setOption(i, { label: val(e) })}
					/>
					<input
						class="insp-input"
						placeholder="Member (optional)"
						value={o.member ?? ''}
						oninput={(e) => setOption(i, { member: opt(val(e)) })}
					/>
					<button
						type="button"
						class="insp-iconbtn"
						aria-label="Remove option"
						onclick={() => removeOption(i)}><Trash2 size={13} /></button
					>
				</div>
			{/each}
		</div>
		<button type="button" class="insp-addbtn" onclick={addOption}>+ Add option</button>
	{/if}

	<div class="insp-label">Filters on</div>
	<label class="insp-row"
		><span>Dimension</span>
		<input
			class="insp-input"
			placeholder="e.g. Store"
			value={pillFilter.dimension}
			oninput={(e) => setFilterPart('dimension', val(e))}
		/>
	</label>
	<label class="insp-row"
		><span>Hierarchy</span>
		<input
			class="insp-input"
			placeholder="e.g. Stores"
			value={pillFilter.hierarchy}
			oninput={(e) => setFilterPart('hierarchy', val(e))}
		/>
	</label>
	<label class="insp-row"
		><span>Level</span>
		<input
			class="insp-input"
			placeholder="e.g. Store Name"
			value={pillFilter.level}
			oninput={(e) => setFilterPart('level', val(e))}
		/>
	</label>
	<p class="insp-hint">
		Leave blank for a purely cosmetic pill — selecting then only changes the header text. Filled in,
		a selection filters every tile in the app.
	</p>

	<button type="button" class="insp-addbtn" onclick={() => setHeader({ contextPill: undefined })}
		>Remove pill</button
	>
</div>

<div class="insp-section">
	<div class="insp-label">Live badge</div>
	<label class="insp-row"
		><span>Text</span>
		<input
			class="insp-input"
			placeholder="e.g. Live · Saiku"
			value={header.liveBadge ?? ''}
			oninput={(e) => setHeader({ liveBadge: opt(val(e)) })}
		/>
	</label>
	<p class="insp-hint">
		Your wording for the connected state. The badge probes the backend and shows it in green when
		connected, amber "Demo data" on a demo instance, and red "Offline" when Saiku can't be reached.
		Leave blank to hide the badge.
	</p>
</div>

<style>
	.ctx-opt {
		display: grid;
		grid-template-columns: 1fr 1fr auto;
		gap: 0.35rem;
	}
	.ctx-warn {
		color: var(--saiku-app-danger, #a3271b);
	}
	code {
		font-size: 0.72rem;
		background: hsl(var(--bg-subtle));
		padding: 0 0.2em;
		border-radius: 3px;
	}
</style>

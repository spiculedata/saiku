<script lang="ts">
	import { untrack } from 'svelte';
	import Modal from '$lib/components/Modal.svelte';
	import MonacoEditor from '$lib/components/MonacoEditor.svelte';
	import ModalModeSwitch from '$lib/modals/parts/ModalModeSwitch.svelte';
	import ModalActions from '$lib/modals/parts/ModalActions.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import type { SaikuMeasure } from '$lib/api/discover';

	/*
	 * "Order axis" picker — two modes.
	 *
	 * - "simple": pick sort direction + a measure from the cube. Emits
	 *   the measure's uniqueName for QueryCanvas to wire into
	 *   queryModel.axes[axis].sortEvaluationLiteral. No MDX authoring.
	 * - "mdx":    advanced escape hatch — Monaco editor with arbitrary
	 *   MDX (calculated expressions, complex tuples). Same downstream
	 *   plumbing; just a different string.
	 *
	 * Mode toggle at the top of the dialog. Default is simple; switching
	 * to MDX seeds the editor with the currently-selected measure's
	 * uniqueName so power users don't start from blank.
	 */
	const SORT_FUNCTIONS = ['ASC', 'BASC', 'DESC', 'BDESC'] as const;
	type SortFn = (typeof SORT_FUNCTIONS)[number];
	type Mode = 'simple' | 'mdx';

	interface Props {
		axis: string;
		measures: SaikuMeasure[];
		initialMeasure: string;
		initialSort: SortFn;
		open: boolean;
		onSave: (expression: string, sort: SortFn) => void;
		onCancel: () => void;
	}

	let { axis, measures, initialMeasure, initialSort, open, onSave, onCancel }: Props = $props();

	let mode = $state<Mode>('simple');
	let selected = $state<string>(untrack(() => initialMeasure));
	let sort = $state<SortFn>(untrack(() => initialSort));
	let mdxBuffer = $state<string>(untrack(() => initialMeasure || ''));

	$effect(() => {
		if (open) {
			// Don't read `selected` here — reading it inside the same effect
			// that wrote it makes the dropdown's bind:value-driven change re-fire
			// this effect and reset the user's pick. Use the source.
			const seedMeasure = initialMeasure || measures[0]?.uniqueName || '';
			mode = 'simple';
			selected = seedMeasure;
			sort = initialSort;
			mdxBuffer = seedMeasure;
		}
	});

	// When the user switches into MDX mode, seed the editor with the
	// simple selection so they have a starting point rather than empty.
	function switchMode(next: Mode) {
		if (next === mode) return;
		if (next === 'mdx' && selected) mdxBuffer = selected;
		mode = next;
	}

	function commit() {
		if (mode === 'simple') {
			if (!selected) return;
			onSave(selected, sort);
		} else {
			const expr = mdxBuffer.trim();
			if (!expr) return;
			onSave(expr, sort);
		}
	}

	const valid = $derived(mode === 'simple' ? !!selected : mdxBuffer.trim().length > 0);
</script>

<Modal
	title={`${i18n.t('modal.filter.order')} ${axis}`}
	{open}
	size={mode === 'mdx' ? 'lg' : 'md'}
	onClose={onCancel}
>
	<ModalModeSwitch {mode} onChange={switchMode} />

	<label class="field">
		<span class="field__label">{i18n.t('modal.filter.sort')}</span>
		<select class="field__input" bind:value={sort}>
			{#each SORT_FUNCTIONS as fn}
				<option value={fn}>{fn}</option>
			{/each}
		</select>
	</label>

	{#if mode === 'simple'}
		<label class="field">
			<span class="field__label">{i18n.t('modal.filter.byMeasure')}</span>
			<select class="field__input" bind:value={selected}>
				{#if !initialMeasure && measures.length > 0}
					<option value="" disabled>{i18n.t('modal.filter.pickMeasure')}</option>
				{/if}
				{#each measures as m}
					<option value={m.uniqueName}>{m.caption || m.name}</option>
				{/each}
			</select>
		</label>
	{:else}
		<div class="field">
			<span class="field__label">Order {i18n.t('modal.filter.mdxExpression')}</span>
			{#if open}
				<MonacoEditor
					value={mdxBuffer}
					language="mdx"
					minHeight="200px"
					onChange={(v) => (mdxBuffer = v)}
				/>
			{/if}
		</div>
	{/if}

	{#snippet footer()}
		<ModalActions {onCancel} onApply={commit} enabled={valid} />
	{/snippet}
</Modal>

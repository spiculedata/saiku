<script lang="ts">
	import { untrack } from 'svelte';
	import Modal from '$lib/components/Modal.svelte';
	import { Button } from '$lib/components/ui';
	import { i18n } from '$lib/stores/i18n.svelte';

	export interface CalculatedMember {
		name: string;
		parent: string;
		formula: string;
		formatString: string;
		dimension?: string;
	}

	interface MeasurePaletteItem {
		caption: string;
		uniqueName: string;
	}

	interface Props {
		initial?: CalculatedMember;
		hierarchies: string[];
		measures?: MeasurePaletteItem[];
		open: boolean;
		onSave: (m: CalculatedMember) => void;
		onCancel: () => void;
	}

	const FORMAT_PRESETS: { label: string; value: string }[] = [
		{ label: 'Integer', value: '#,##0' },
		{ label: 'Decimal (2)', value: '#,##0.00' },
		{ label: 'Decimal (4)', value: '#,##0.0000' },
		{ label: 'Percentage', value: '0.00%' },
		{ label: 'Currency (USD)', value: '$#,##0.00' },
		{ label: 'Currency (EUR)', value: '€#,##0.00' },
		{ label: 'Scientific', value: '0.00E+00' },
		{ label: 'Plain', value: 'Standard' }
	];

	const OPERATORS = ['+', '-', '*', '/', '(', ')', ',', '=', '>', '<', '>=', '<='];

	const FUNCTION_TEMPLATES: { label: string; snippet: string; cursor: number }[] = [
		{ label: 'IIF', snippet: 'IIF(<cond>, <then>, <else>)', cursor: 4 },
		{ label: 'CASE', snippet: 'CASE WHEN <cond> THEN <val> ELSE <val> END', cursor: 10 },
		{ label: 'SUM', snippet: 'SUM(<set>)', cursor: 4 },
		{ label: 'AVG', snippet: 'AVG(<set>)', cursor: 4 },
		{ label: 'MIN', snippet: 'MIN(<set>)', cursor: 4 },
		{ label: 'MAX', snippet: 'MAX(<set>)', cursor: 4 },
		{ label: 'COUNT', snippet: 'COUNT(<set>)', cursor: 6 },
		{ label: 'RANK', snippet: 'RANK(<member>, <set>)', cursor: 5 },
		{
			label: 'ParallelPeriod',
			snippet: 'PARALLELPERIOD([Time].[Time].[Quarter], 1, <member>)',
			cursor: 15
		},
		{ label: 'CurrentMember', snippet: '[Time].[Time].CurrentMember', cursor: 27 },
		{ label: 'PrevMember', snippet: '<member>.PrevMember', cursor: 8 },
		{ label: 'Children', snippet: '<member>.Children', cursor: 8 }
	];

	let {
		initial = { name: '', parent: '[Measures]', formula: '', formatString: '#,##0.00' },
		hierarchies,
		measures = [],
		open,
		onSave,
		onCancel
	}: Props = $props();

	let form = $state<CalculatedMember>(untrack(() => ({ ...initial })));
	let textarea: HTMLTextAreaElement | null = null;
	let measureFilter = $state('');

	$effect(() => {
		if (open) form = { ...initial };
	});

	const filteredMeasures = $derived(
		measureFilter
			? measures.filter((m) => m.caption.toLowerCase().includes(measureFilter.toLowerCase()))
			: measures
	);

	function insertAtCursor(text: string, caretOffset?: number) {
		if (!textarea) {
			form.formula = (form.formula ?? '') + text;
			return;
		}
		const start = textarea.selectionStart ?? form.formula.length;
		const end = textarea.selectionEnd ?? start;
		const next = form.formula.slice(0, start) + text + form.formula.slice(end);
		form.formula = next;
		const caret = start + (caretOffset ?? text.length);
		queueMicrotask(() => {
			if (!textarea) return;
			textarea.focus();
			textarea.setSelectionRange(caret, caret);
		});
	}

	const validName = $derived(/^[A-Za-z_][A-Za-z0-9_ -]*$/.test(form.name.trim()));
	const valid = $derived(validName && form.formula.trim().length > 0);

	function hierarchyOptions(): string[] {
		const opts = [...new Set(['[Measures]', ...(hierarchies ?? [])])];
		return opts;
	}
</script>

<Modal title={i18n.t('modal.calc.title')} {open} size="lg" onClose={onCancel}>
	<div class="wizard">
		<div class="flex min-w-0 flex-col gap-3">
			<label class="field">
				<span class="field__label">{i18n.t('modal.calc.name')}</span>
				<input
					class="field__input"
					bind:value={form.name}
					placeholder={i18n.t('modal.calc.namePlaceholder')}
					aria-invalid={form.name.trim().length > 0 && !validName}
				/>
				{#if form.name.trim().length > 0 && !validName}
					<span class="text-xs text-danger">{i18n.t('modal.calc.nameError')}</span>
				{/if}
			</label>

			<label class="field">
				<span class="field__label">{i18n.t('modal.calc.parentHierarchy')}</span>
				<select class="field__input" bind:value={form.parent}>
					{#each hierarchyOptions() as h}
						<option value={h}>{h}</option>
					{/each}
				</select>
				<span class="text-xs text-fg-subtle">{i18n.t('modal.calc.parentHint')}</span>
			</label>

			<label class="field">
				<span class="field__label">{i18n.t('modal.calc.formula')}</span>
				<textarea
					class="field__input formula"
					rows="6"
					bind:value={form.formula}
					bind:this={textarea}
					placeholder={i18n.t('modal.calc.formulaPlaceholder')}
				></textarea>
			</label>

			<label class="field">
				<span class="field__label">{i18n.t('modal.calc.formatString')}</span>
				<div class="format-row">
					<input class="field__input" bind:value={form.formatString} />
					<select
						class="field__input max-w-[220px]"
						onchange={(e) => {
							const v = (e.target as HTMLSelectElement).value;
							if (v) form.formatString = v;
						}}
						value=""
					>
						<option value="">{i18n.t('modal.calc.presets')}</option>
						{#each FORMAT_PRESETS as p}
							<option value={p.value}>{p.label} ({p.value})</option>
						{/each}
					</select>
				</div>
			</label>

			<div class="preview">
				<div class="preview__label">{i18n.t('modal.calc.mdxPreview')}</div>
				<pre>WITH MEMBER {form.parent}.[{form.name.trim() || '…'}] AS '{form.formula.trim() ||
						'…'}',
  FORMAT_STRING = '{form.formatString}', SOLVE_ORDER = 200
SELECT …</pre>
			</div>
		</div>

		<div class="flex min-w-0 flex-col gap-3">
			<div class="palette">
				<div class="palette__title">{i18n.t('panels.measures')}</div>
				<input
					class="field__input m-0"
					bind:value={measureFilter}
					placeholder={i18n.t('modal.calc.filterMeasures')}
				/>
				<ul class="m-0 max-h-[220px] list-none overflow-y-auto p-0">
					{#each filteredMeasures as m}
						<li>
							<button
								type="button"
								class="palette__btn"
								title={m.uniqueName}
								onclick={() => insertAtCursor(m.uniqueName)}>{m.caption}</button
							>
						</li>
					{/each}
					{#if filteredMeasures.length === 0}
						<li class="palette__empty">{i18n.t('panels.noMeasures')}</li>
					{/if}
				</ul>
			</div>

			<div class="palette">
				<div class="palette__title">{i18n.t('modal.calc.operators')}</div>
				<div class="palette__grid">
					{#each OPERATORS as op}
						<button type="button" class="palette__chip" onclick={() => insertAtCursor(` ${op} `)}
							>{op}</button
						>
					{/each}
				</div>
			</div>

			<div class="palette">
				<div class="palette__title">{i18n.t('modal.calc.functions')}</div>
				<ul class="m-0 max-h-[220px] list-none overflow-y-auto p-0">
					{#each FUNCTION_TEMPLATES as fn}
						<li>
							<button
								type="button"
								class="palette__btn"
								title={fn.snippet}
								onclick={() => insertAtCursor(fn.snippet, fn.cursor)}>{fn.label}</button
							>
						</li>
					{/each}
				</ul>
			</div>
		</div>
	</div>
	{#snippet footer()}
		<Button variant="outline" onclick={onCancel}>{i18n.t('modal.cancel')}</Button>
		<Button
			disabled={!valid}
			onclick={() => onSave({ ...form, name: form.name.trim(), formula: form.formula.trim() })}
			>{i18n.t('modal.save')}</Button
		>
	{/snippet}
</Modal>

<style>
	.wizard {
		display: grid;
		grid-template-columns: minmax(0, 1.4fr) minmax(220px, 1fr);
		gap: var(--space-4);
	}
	.formula {
		font-family: ui-monospace, 'SF Mono', Menlo, monospace;
		font-size: var(--fs-sm);
		resize: vertical;
	}
	.format-row {
		display: flex;
		gap: var(--space-2);
	}
	.preview {
		background: hsl(var(--bg-muted));
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		padding: var(--space-2) var(--space-3);
	}
	.preview__label {
		font-size: var(--fs-xs);
		color: hsl(var(--fg-subtle));
		text-transform: uppercase;
		letter-spacing: 0.06em;
		margin-bottom: 4px;
	}
	.preview pre {
		margin: 0;
		font-size: var(--fs-xs);
		white-space: pre-wrap;
		color: hsl(var(--fg-muted));
	}
	.palette {
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		background: hsl(var(--bg-muted));
		padding: var(--space-2);
		display: flex;
		flex-direction: column;
		gap: var(--space-1);
	}
	.palette__title {
		font-size: var(--fs-xs);
		color: hsl(var(--fg-subtle));
		text-transform: uppercase;
		letter-spacing: 0.06em;
	}
	.palette__btn {
		width: 100%;
		text-align: left;
		background: transparent;
		border: 0;
		color: hsl(var(--fg));
		padding: 3px 6px;
		border-radius: var(--radius-sm);
		cursor: pointer;
		font: inherit;
	}
	.palette__btn:hover {
		background: hsl(var(--bg-subtle));
	}
	.palette__empty {
		color: hsl(var(--fg-subtle));
		padding: 3px 6px;
		font-size: var(--fs-xs);
	}
	.palette__grid {
		display: grid;
		grid-template-columns: repeat(6, minmax(0, 1fr));
		gap: 4px;
	}
	.palette__chip {
		background: hsl(var(--bg));
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		padding: 2px 0;
		color: hsl(var(--fg));
		font: inherit;
		cursor: pointer;
	}
	.palette__chip:hover {
		background: hsl(var(--bg-subtle));
	}
</style>

<script lang="ts">
	import { untrack } from 'svelte';
	import Modal from '$lib/components/Modal.svelte';
	import { Button } from '$lib/components/ui';
	import { i18n } from '$lib/stores/i18n.svelte';
	import type {
		ConditionalFormatRule,
		ConditionalFormatType,
		ConditionalThresholdMode,
		ConditionalBandColors
	} from '$lib/api/dashboards';
	import { DEFAULT_BAND_COLORS } from '$lib/dashboard/conditionalFormat';

	interface Props {
		open: boolean;
		rules: ConditionalFormatRule[];
		columns: string[];
		onSave: (rules: ConditionalFormatRule[]) => void;
		onCancel: () => void;
	}

	let { open, rules, columns, onSave, onCancel }: Props = $props();
	let form = $state<ConditionalFormatRule[]>(untrack(() => rules.map((r) => ({ ...r }))));

	$effect(() => {
		if (open) form = rules.map((r) => ({ ...r }));
	});

	const types: ConditionalFormatType[] = ['background', 'font', 'icon', 'bar'];
	const modes: ConditionalThresholdMode[] = ['absolute', 'relative'];

	function addRule() {
		form = [
			...form,
			{
				column: columns[0] ?? '',
				type: 'background',
				thresholdMode: 'absolute',
				lowThreshold: 0,
				highThreshold: 0
			}
		];
	}

	function usesThresholds(rule: ConditionalFormatRule): boolean {
		return rule.type === 'background' || rule.type === 'font' || rule.type === 'icon';
	}

	function setBand(rule: ConditionalFormatRule, band: keyof ConditionalBandColors, value: string) {
		rule.colors = { ...rule.colors, [band]: value };
	}
</script>

<Modal title={i18n.t('modal.cellFormat.title')} {open} size="lg" onClose={onCancel}>
	<p class="mb-3 text-sm text-fg-muted">{i18n.t('modal.cellFormat.hint')}</p>
	{#if form.length === 0}
		<p class="text-sm text-fg-muted">{i18n.t('modal.cellFormat.empty')}</p>
	{/if}
	{#each form as rule, i (i)}
		<div class="mb-3 grid grid-cols-2 gap-2 rounded border border-border p-2">
			<label class="field">
				<span class="field__label">{i18n.t('modal.cellFormat.column')}</span>
				<select class="field__input" bind:value={rule.column}>
					{#each columns as col (col)}
						<option value={col}>{col}</option>
					{/each}
				</select>
			</label>
			<label class="field">
				<span class="field__label">{i18n.t('modal.cellFormat.type')}</span>
				<select class="field__input" bind:value={rule.type}>
					{#each types as t (t)}
						<option value={t}>{i18n.t(`modal.cellFormat.type.${t}`)}</option>
					{/each}
				</select>
			</label>
			{#if usesThresholds(rule)}
				<label class="field">
					<span class="field__label">{i18n.t('modal.cellFormat.mode')}</span>
					<select class="field__input" bind:value={rule.thresholdMode}>
						{#each modes as m (m)}
							<option value={m}>{i18n.t(`modal.cellFormat.mode.${m}`)}</option>
						{/each}
					</select>
				</label>
				<div class="grid grid-cols-2 gap-2">
					<label class="field">
						<span class="field__label">{i18n.t('modal.cellFormat.low')}</span>
						<input class="field__input" type="number" bind:value={rule.lowThreshold} />
					</label>
					<label class="field">
						<span class="field__label">{i18n.t('modal.cellFormat.high')}</span>
						<input class="field__input" type="number" bind:value={rule.highThreshold} />
					</label>
				</div>
			{/if}
			{#if usesThresholds(rule)}
				<div class="col-span-2 flex flex-wrap gap-4">
					<label class="flex items-center gap-2 text-sm">
						<input
							type="color"
							value={rule.colors?.low ?? DEFAULT_BAND_COLORS.low}
							oninput={(e) => setBand(rule, 'low', e.currentTarget.value)}
						/>
						{i18n.t('modal.cellFormat.colorLow')}
					</label>
					<label class="flex items-center gap-2 text-sm">
						<input
							type="color"
							value={rule.colors?.mid ?? DEFAULT_BAND_COLORS.mid}
							oninput={(e) => setBand(rule, 'mid', e.currentTarget.value)}
						/>
						{i18n.t('modal.cellFormat.colorMid')}
					</label>
					<label class="flex items-center gap-2 text-sm">
						<input
							type="color"
							value={rule.colors?.high ?? DEFAULT_BAND_COLORS.high}
							oninput={(e) => setBand(rule, 'high', e.currentTarget.value)}
						/>
						{i18n.t('modal.cellFormat.colorHigh')}
					</label>
				</div>
			{:else}
				<label class="col-span-2 flex items-center gap-2 text-sm">
					<input
						type="color"
						value={rule.barColor ?? '#4c8dff'}
						oninput={(e) => (rule.barColor = e.currentTarget.value)}
					/>
					{i18n.t('modal.cellFormat.barColor')}
				</label>
			{/if}
			<button
				type="button"
				class="text-left text-sm text-fg-muted"
				onclick={() => (form = form.filter((_, j) => j !== i))}
			>
				{i18n.t('modal.cellFormat.remove')}
			</button>
		</div>
	{/each}
	{#snippet footer()}
		<Button variant="outline" onclick={addRule}>{i18n.t('modal.cellFormat.add')}</Button>
		<div class="flex-1"></div>
		<Button variant="outline" onclick={onCancel}>{i18n.t('modal.cancel')}</Button>
		<Button onclick={() => onSave(form)}>{i18n.t('modal.save')}</Button>
	{/snippet}
</Modal>

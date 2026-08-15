<script lang="ts">
	import { Button } from '$lib/components/ui';
	/*
	 * Table-tile conditional formatting — extracted from TileEditorModal.svelte
	 * (saiku#1229; original feature saiku#919). Per-column rule builder:
	 * background colour, data bar, font colour, or icon. Display-only — the
	 * underlying data is unchanged. Match a rule to a column by its header
	 * caption.
	 *
	 * Rule maths lives in $lib/dashboard/conditionalFormat.ts; this is config
	 * capture only. State held as a $bindable() array prop so the parent
	 * persists.
	 */
	import type {
		ConditionalFormatRule,
		ConditionalFormatType,
		ConditionalThresholdMode
	} from '$lib/api/dashboards';

	interface Props {
		conditionalFormat: ConditionalFormatRule[];
	}
	let { conditionalFormat = $bindable() }: Props = $props();

	function addRule(): void {
		conditionalFormat = [
			...conditionalFormat,
			{
				column: '',
				type: 'background',
				thresholdMode: 'relative',
				lowThreshold: 25,
				highThreshold: 75
			}
		];
	}

	function removeRule(index: number): void {
		conditionalFormat = conditionalFormat.filter((_, i) => i !== index);
	}

	/** A rule needs explicit thresholds for background; font/icon may run in
	 *  sign mode (no thresholds). bar never uses thresholds. */
	function ruleUsesThresholds(r: ConditionalFormatRule): boolean {
		return r.type === 'background' || r.type === 'font' || r.type === 'icon';
	}

	const CONDITIONAL_TYPES: { id: ConditionalFormatType; label: string }[] = [
		{ id: 'background', label: 'Background colour' },
		{ id: 'bar', label: 'Data bar' },
		{ id: 'font', label: 'Font colour' },
		{ id: 'icon', label: 'Icon (↑ ↓ →)' }
	];

	const CONDITIONAL_MODES: { id: ConditionalThresholdMode; label: string }[] = [
		{ id: 'relative', label: 'Relative (percentile)' },
		{ id: 'absolute', label: 'Absolute (fixed value)' }
	];
</script>

<fieldset class="cf-section">
	<legend>Conditional formatting (per column)</legend>
	<span class="hint">
		Display-only — the underlying data is unchanged. Match a rule to a column by its header caption.
	</span>

	{#if conditionalFormat.length === 0}
		<span class="hint">No rules yet.</span>
	{/if}

	{#each conditionalFormat as cfRule, i (i)}
		<div class="cf-rule">
			<div class="flex items-center justify-between">
				<span class="cf-rule-label">Rule {i + 1}</span>
				<button
					type="button"
					class="cursor-pointer border-0 bg-transparent text-lg leading-none text-fg-muted"
					aria-label="Remove rule"
					onclick={() => removeRule(i)}>×</button
				>
			</div>

			<label class="field">
				<span class="field__label">Column (header caption)</span>
				<input
					class="field__input"
					type="text"
					bind:value={cfRule.column}
					placeholder="e.g. Unit Sales"
				/>
			</label>

			<div class="flex items-end gap-2">
				<label class="field flex-1">
					<span class="field__label">Format</span>
					<select class="field__input" bind:value={cfRule.type}>
						{#each CONDITIONAL_TYPES as t (t.id)}
							<option value={t.id}>{t.label}</option>
						{/each}
					</select>
				</label>

				{#if ruleUsesThresholds(cfRule)}
					<label class="field flex-1">
						<span class="field__label">Threshold mode</span>
						<select class="field__input" bind:value={cfRule.thresholdMode}>
							{#each CONDITIONAL_MODES as m (m.id)}
								<option value={m.id}>{m.label}</option>
							{/each}
						</select>
					</label>
				{/if}
			</div>

			{#if ruleUsesThresholds(cfRule)}
				<div class="flex items-end gap-2">
					<label class="field flex-1">
						<span class="field__label">
							Low {cfRule.thresholdMode === 'relative' ? '(percentile)' : '(value)'}
						</span>
						<input
							class="field__input"
							type="number"
							bind:value={cfRule.lowThreshold}
							placeholder="off"
						/>
					</label>
					<label class="field flex-1">
						<span class="field__label">
							High {cfRule.thresholdMode === 'relative' ? '(percentile)' : '(value)'}
						</span>
						<input
							class="field__input"
							type="number"
							bind:value={cfRule.highThreshold}
							placeholder="off"
						/>
					</label>
				</div>
				{#if cfRule.type === 'font' || cfRule.type === 'icon'}
					<span class="hint">
						Leave both thresholds empty to colour / point by sign (negative = red / ↓, positive =
						green / ↑).
					</span>
				{/if}
			{/if}

			{#if cfRule.type === 'bar'}
				<label class="field">
					<span class="field__label">Bar colour</span>
					<input
						class="field__input"
						type="text"
						bind:value={cfRule.barColor}
						placeholder="#4c8dff"
					/>
				</label>
			{/if}

			{#if cfRule.type === 'background' || cfRule.type === 'font' || cfRule.type === 'icon'}
				<div class="flex items-end gap-2">
					<label class="field flex-1">
						<span class="field__label">Low colour</span>
						<input
							class="field__input"
							type="text"
							value={cfRule.colors?.low ?? ''}
							placeholder="default red"
							oninput={(e) => {
								const v = (e.target as HTMLInputElement).value;
								cfRule.colors = { ...cfRule.colors, low: v || undefined };
							}}
						/>
					</label>
					<label class="field flex-1">
						<span class="field__label">Mid colour</span>
						<input
							class="field__input"
							type="text"
							value={cfRule.colors?.mid ?? ''}
							placeholder="default amber"
							oninput={(e) => {
								const v = (e.target as HTMLInputElement).value;
								cfRule.colors = { ...cfRule.colors, mid: v || undefined };
							}}
						/>
					</label>
					<label class="field flex-1">
						<span class="field__label">High colour</span>
						<input
							class="field__input"
							type="text"
							value={cfRule.colors?.high ?? ''}
							placeholder="default green"
							oninput={(e) => {
								const v = (e.target as HTMLInputElement).value;
								cfRule.colors = { ...cfRule.colors, high: v || undefined };
							}}
						/>
					</label>
				</div>
			{/if}
		</div>
	{/each}

	<Button variant="outline" class="cf-add" onclick={addRule}>+ Add column rule</Button>
</fieldset>

<style>
	/* Duplicated from the parent TileEditorModal — Svelte's scoped CSS
     does not cross component boundaries. Co-locating the rule-card look
     here keeps the conditional-format editor self-contained. */
	.cf-section {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		border: 1px solid hsl(var(--border));
		border-radius: 4px;
		padding: 0.5rem 0.75rem;
		margin: 0;
	}
	.cf-section legend {
		font-size: 0.75rem;
		color: hsl(var(--fg-muted));
		text-transform: uppercase;
		letter-spacing: 0.04em;
		padding: 0 0.25rem;
	}
	.cf-rule {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		border: 1px solid hsl(var(--border));
		border-radius: 4px;
		padding: 0.5rem 0.625rem;
		background: hsl(var(--bg-subtle));
	}
	.cf-rule-label {
		font-size: 0.75rem;
		color: hsl(var(--fg-muted));
		text-transform: uppercase;
		letter-spacing: 0.04em;
	}
</style>

<script lang="ts">
	import { untrack } from 'svelte';
	import Modal from '$lib/components/Modal.svelte';
	import { Button } from '$lib/components/ui';
	import { i18n } from '$lib/stores/i18n.svelte';
	import type { ThinNamedSet } from '$lib/api/query';

	interface Props {
		initial?: ThinNamedSet;
		open: boolean;
		onSave: (s: ThinNamedSet) => void;
		onCancel: () => void;
	}

	const BLANK: ThinNamedSet = { name: '', expression: '', caption: '' };

	let { initial = BLANK, open, onSave, onCancel }: Props = $props();

	let form = $state<ThinNamedSet>(untrack(() => ({ ...initial })));

	$effect(() => {
		if (open) form = { ...initial };
	});

	// Same identifier shape as CalculatedMemberModal's name field — bare
	// Mondrian identifier, no brackets (the panel/store add those when
	// building the MDX `[Name]` reference).
	const validName = $derived(/^[A-Za-z_][A-Za-z0-9_ -]*$/.test(form.name.trim()));
	const valid = $derived(validName && form.expression.trim().length > 0);
</script>

<Modal title={i18n.t('modal.set.title')} {open} size="md" onClose={onCancel}>
	<div class="flex flex-col gap-3">
		<label class="field">
			<span class="field__label">{i18n.t('modal.set.name')}</span>
			<input
				class="field__input"
				bind:value={form.name}
				placeholder={i18n.t('modal.set.namePlaceholder')}
				aria-invalid={form.name.trim().length > 0 && !validName}
			/>
			{#if form.name.trim().length > 0 && !validName}
				<span class="text-xs text-danger">{i18n.t('modal.set.nameError')}</span>
			{/if}
		</label>

		<label class="field">
			<span class="field__label">{i18n.t('modal.set.caption')}</span>
			<input
				class="field__input"
				bind:value={form.caption}
				placeholder={i18n.t('modal.set.captionPlaceholder')}
			/>
		</label>

		<label class="field">
			<span class="field__label">{i18n.t('modal.set.expression')}</span>
			<textarea
				class="field__input formula"
				rows="6"
				bind:value={form.expression}
				placeholder={i18n.t('modal.set.expressionPlaceholder')}></textarea>
		</label>

		<div class="preview">
			<div class="preview__label">{i18n.t('modal.set.mdxPreview')}</div>
			<pre>WITH SET [{form.name.trim() || '…'}] AS '{form.expression.trim() || '…'}'
SELECT …</pre>
		</div>
	</div>
	{#snippet footer()}
		<Button variant="outline" onclick={onCancel}>{i18n.t('modal.cancel')}</Button>
		<Button
			disabled={!valid}
			onclick={() =>
				onSave({
					name: form.name.trim(),
					expression: form.expression.trim(),
					caption: form.caption?.trim() || undefined
				})}>{i18n.t('modal.save')}</Button
		>
	{/snippet}
</Modal>

<style>
	.formula {
		font-family: ui-monospace, 'SF Mono', Menlo, monospace;
		font-size: var(--fs-sm);
		resize: vertical;
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
</style>

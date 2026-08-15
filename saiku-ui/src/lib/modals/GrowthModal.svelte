<script lang="ts">
	import Modal from '$lib/components/Modal.svelte';
	import ModalActions from '$lib/modals/parts/ModalActions.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';

	/** Port of saiku-ui-legacy/js/saiku/views/GrowthModal.js. */
	export type GrowthBasis = 'previous' | 'first' | 'specific';

	interface Props {
		open: boolean;
		onApply: (basis: GrowthBasis, referenceValue?: string) => void;
		onCancel: () => void;
	}

	let { open, onApply, onCancel }: Props = $props();
	let basis = $state<GrowthBasis>('previous');
	let reference = $state<string>('');

	$effect(() => {
		if (open) {
			basis = 'previous';
			reference = '';
		}
	});
</script>

<Modal title={i18n.t('modal.growth.title')} {open} size="md" onClose={onCancel}>
	<fieldset class="field">
		<legend class="field__label">{i18n.t('modal.growth.compareAgainst')}</legend>
		<label class="flex cursor-pointer items-center gap-2 px-0 py-1"
			><input type="radio" name="basis" value="previous" bind:group={basis} />
			{i18n.t('modal.growth.previousPeriod')}</label
		>
		<label class="flex cursor-pointer items-center gap-2 px-0 py-1"
			><input type="radio" name="basis" value="first" bind:group={basis} />
			{i18n.t('modal.growth.firstPeriod')}</label
		>
		<label class="flex cursor-pointer items-center gap-2 px-0 py-1"
			><input type="radio" name="basis" value="specific" bind:group={basis} />
			{i18n.t('modal.growth.specificMember')}</label
		>
	</fieldset>
	{#if basis === 'specific'}
		<label class="field">
			<span class="field__label">{i18n.t('modal.growth.referenceMember')}</span>
			<input class="field__input" bind:value={reference} />
		</label>
	{/if}
	{#snippet footer()}
		<ModalActions
			{onCancel}
			onApply={() => onApply(basis, basis === 'specific' ? reference : undefined)}
			primaryKey="modal.apply"
		/>
	{/snippet}
</Modal>

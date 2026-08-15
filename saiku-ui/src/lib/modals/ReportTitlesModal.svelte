<script lang="ts">
	import { untrack } from 'svelte';
	import Modal from '$lib/components/Modal.svelte';
	import { Button } from '$lib/components/ui';
	import { i18n } from '$lib/stores/i18n.svelte';

	/** Port of saiku-ui-legacy/js/saiku/views/ReportTitlesModal.js. */
	export interface ReportTitles {
		title: string;
		subtitle: string;
		notes: string;
	}

	interface Props {
		titles: ReportTitles;
		open: boolean;
		onSave: (t: ReportTitles) => void;
		onCancel: () => void;
	}

	let { titles, open, onSave, onCancel }: Props = $props();
	let form = $state<ReportTitles>(untrack(() => ({ ...titles })));

	$effect(() => {
		if (open) form = { ...titles };
	});
</script>

<Modal title={i18n.t('modal.reportTitles.title')} {open} size="md" onClose={onCancel}>
	<label class="field">
		<span class="field__label">{i18n.t('modal.reportTitles.titleLabel')}</span>
		<input class="field__input" bind:value={form.title} />
	</label>
	<label class="field">
		<span class="field__label">{i18n.t('modal.reportTitles.subtitle')}</span>
		<input class="field__input" bind:value={form.subtitle} />
	</label>
	<label class="field">
		<span class="field__label">{i18n.t('modal.reportTitles.notes')}</span>
		<textarea class="field__input" rows="4" bind:value={form.notes}></textarea>
	</label>
	{#snippet footer()}
		<Button variant="outline" onclick={onCancel}>{i18n.t('modal.cancel')}</Button>
		<Button onclick={() => onSave(form)}>{i18n.t('modal.save')}</Button>
	{/snippet}
</Modal>

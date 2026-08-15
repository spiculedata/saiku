<script lang="ts">
	import Modal from '$lib/components/Modal.svelte';
	import { Button } from '$lib/components/ui';
	import { i18n } from '$lib/stores/i18n.svelte';

	/** Port of saiku-ui-legacy/js/saiku/views/WarningModal.js. */
	interface Props {
		title?: string;
		message: string;
		open: boolean;
		onClose: () => void;
	}

	let { title = i18n.t('modal.warning.title'), message, open, onClose }: Props = $props();
</script>

<Modal {title} {open} size="sm" {onClose}>
	<p class="callout callout--danger">{message}</p>
	{#snippet footer()}
		<Button onclick={onClose}>{i18n.t('modal.ok')}</Button>
	{/snippet}
</Modal>

<script lang="ts">
	import ConfirmModal from '$lib/modals/ConfirmModal.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';

	/** Port of saiku-ui-legacy/js/saiku/views/OverwriteModal.js. */
	interface Props {
		targetPath: string;
		open: boolean;
		onConfirm: () => void;
		onCancel: () => void;
	}

	let { targetPath, open, onConfirm, onCancel }: Props = $props();
</script>

<ConfirmModal
	title={i18n.t('modal.overwrite.title')}
	message={i18n.t('modal.overwrite.message').replace('{path}', targetPath)}
	confirmLabel={i18n.t('modal.overwrite.confirm')}
	cancelLabel={i18n.t('modal.cancel')}
	variant="danger"
	{open}
	{onConfirm}
	{onCancel}
/>

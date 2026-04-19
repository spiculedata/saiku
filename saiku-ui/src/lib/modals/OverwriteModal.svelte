<script lang="ts">
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";

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
  title="File exists"
  message={`An object already exists at ${targetPath}. Overwrite?`}
  confirmLabel="Overwrite"
  cancelLabel="Cancel"
  variant="danger"
  {open}
  {onConfirm}
  {onCancel}
/>

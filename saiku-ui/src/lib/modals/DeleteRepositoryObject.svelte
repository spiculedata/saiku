<script lang="ts">
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/DeleteRepositoryObject.js. */
  interface Props {
    path: string;
    kind: "file" | "folder";
    open: boolean;
    onConfirm: () => void;
    onCancel: () => void;
  }

  let { path, kind, open, onConfirm, onCancel }: Props = $props();
</script>

<ConfirmModal
  title="Delete {kind}"
  message={`Permanently delete ${kind} "${path}"? This cannot be undone.`}
  confirmLabel="Delete"
  cancelLabel="Cancel"
  variant="danger"
  {open}
  {onConfirm}
  {onCancel}
/>

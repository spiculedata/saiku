<script lang="ts">
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/DeleteRepositoryObject.js. */
  interface Props {
    path: string;
    kind: "file" | "folder";
    open: boolean;
    onConfirm: () => void;
    onCancel: () => void;
  }

  let { path, kind, open, onConfirm, onCancel }: Props = $props();
  const kindLabel = $derived(
    kind === "folder" ? i18n.t("modal.delete.folder") : i18n.t("modal.delete.file"),
  );
</script>

<ConfirmModal
  title={`${i18n.t("modal.delete")} ${kindLabel}`}
  message={`${i18n.t("modal.delete.confirm").replace("{kind}", kindLabel).replace("{path}", path)}`}
  confirmLabel={i18n.t("modal.delete")}
  cancelLabel={i18n.t("modal.cancel")}
  variant="danger"
  {open}
  {onConfirm}
  {onCancel}
/>

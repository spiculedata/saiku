<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { i18n } from "$lib/stores/i18n.svelte";

  interface Props {
    title: string;
    message: string;
    confirmLabel?: string;
    cancelLabel?: string;
    variant?: "default" | "danger";
    open: boolean;
    onConfirm: () => void;
    onCancel: () => void;
  }

  let {
    title,
    message,
    confirmLabel = i18n.t("modal.confirm"),
    cancelLabel = i18n.t("modal.cancel"),
    variant = "default",
    open,
    onConfirm,
    onCancel,
  }: Props = $props();
</script>

<Modal {title} {open} size="sm" onClose={onCancel}>
  <p>{message}</p>
  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{cancelLabel}</Button>
    <Button variant={variant === "danger" ? "destructive" : "default"} onclick={onConfirm}>{confirmLabel}</Button>
  {/snippet}
</Modal>

<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

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
    confirmLabel = "Confirm",
    cancelLabel = "Cancel",
    variant = "default",
    open,
    onConfirm,
    onCancel,
  }: Props = $props();
</script>

<Modal {title} {open} size="sm" onClose={onCancel}>
  <p>{message}</p>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{cancelLabel}</button>
    <button
      type="button"
      class={variant === "danger" ? "btn btn--danger" : "btn btn--primary"}
      onclick={onConfirm}
    >{confirmLabel}</button>
  {/snippet}
</Modal>

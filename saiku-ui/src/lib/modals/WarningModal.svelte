<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/WarningModal.js. */
  interface Props {
    title?: string;
    message: string;
    open: boolean;
    onClose: () => void;
  }

  let { title = "Warning", message, open, onClose }: Props = $props();
</script>

<Modal {title} {open} size="sm" {onClose}>
  <p class="callout callout--danger">{message}</p>
  {#snippet footer()}
    <button type="button" class="btn btn--primary" onclick={onClose}>OK</button>
  {/snippet}
</Modal>

<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/SessionErrorModal.js. */
  interface Props {
    message: string;
    open: boolean;
    onReload: () => void;
  }

  let { message, open, onReload }: Props = $props();
</script>

<Modal title="Session ended" {open} size="sm" onClose={onReload}>
  <p class="callout callout--danger">{message}</p>
  <p>Your session has expired or been terminated. Sign in again to continue.</p>
  {#snippet footer()}
    <button type="button" class="btn btn--primary" onclick={onReload}>Reload</button>
  {/snippet}
</Modal>

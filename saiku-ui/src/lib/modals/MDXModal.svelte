<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/MDXModal.js. Monaco swap
   * arrives in the editor slice; for now a textarea with monospace font
   * mirrors the legacy behaviour (pretty-printed MDX, copy, execute). */
  interface Props {
    mdx: string;
    open: boolean;
    onRun: (mdx: string) => void;
    onCancel: () => void;
  }

  let { mdx, open, onRun, onCancel }: Props = $props();
  let buffer = $state<string>(mdx);

  $effect(() => {
    if (open) buffer = mdx;
  });

  function copy() {
    navigator.clipboard?.writeText(buffer);
  }
</script>

<Modal title="MDX query" {open} size="xl" onClose={onCancel}>
  <label class="field">
    <span class="field__label">MDX</span>
    <textarea class="field__input mdx" bind:value={buffer} spellcheck="false" rows="16"></textarea>
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={copy}>Copy</button>
    <button type="button" class="btn" onclick={onCancel}>Close</button>
    <button type="button" class="btn btn--primary" onclick={() => onRun(buffer)}>Run MDX</button>
  {/snippet}
</Modal>

<style>
  .mdx {
    font-family: var(--font-mono);
    font-size: var(--fs-sm);
    min-height: 280px;
    resize: vertical;
  }
</style>

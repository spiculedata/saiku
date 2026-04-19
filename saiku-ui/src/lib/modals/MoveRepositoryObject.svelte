<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/MoveRepositoryObject.js. */
  interface Props {
    sourcePath: string;
    folders: string[];
    open: boolean;
    onMove: (targetFolder: string) => void;
    onCancel: () => void;
  }

  let { sourcePath, folders, open, onMove, onCancel }: Props = $props();
  let target = $state<string>(folders[0] ?? "");
</script>

<Modal title="Move item" {open} size="md" onClose={onCancel}>
  <p class="hint">Move <code>{sourcePath}</code> to:</p>
  <label class="field">
    <span class="field__label">Destination folder</span>
    <select class="field__input" bind:value={target}>
      {#each folders as f}
        <option value={f}>{f || "/"}</option>
      {/each}
    </select>
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button type="button" class="btn btn--primary" onclick={() => onMove(target)}>Move</button>
  {/snippet}
</Modal>

<style>
  .hint { color: var(--fg-muted); font-size: var(--fs-sm); margin: 0 0 var(--space-3); }
  code {
    background: var(--bg-subtle);
    padding: 0 var(--space-1);
    border-radius: var(--radius-sm);
    font-family: var(--font-mono);
    font-size: 12px;
  }
</style>

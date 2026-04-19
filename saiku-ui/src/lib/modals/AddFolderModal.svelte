<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/AddFolderModal.js. */
  interface Props {
    parentPath: string;
    open: boolean;
    onCreate: (name: string) => void;
    onCancel: () => void;
  }

  let { parentPath, open, onCreate, onCancel }: Props = $props();
  let name = $state("");

  function submit(e: Event) {
    e.preventDefault();
    if (name.trim()) onCreate(name.trim());
  }
</script>

<Modal title="New folder" {open} size="sm" onClose={onCancel}>
  <form onsubmit={submit}>
    <p class="hint">Create under <code>{parentPath || "/"}</code>.</p>
    <label class="field">
      <span class="field__label">Folder name</span>
      <input class="field__input" bind:value={name} autocomplete="off" required />
    </label>
  </form>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button
      type="button"
      class="btn btn--primary"
      disabled={!name.trim()}
      onclick={() => onCreate(name.trim())}
    >Create</button>
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

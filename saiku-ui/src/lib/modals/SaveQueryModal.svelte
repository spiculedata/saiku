<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/SaveQuery.js. */
  interface Props {
    defaultName: string;
    defaultFolder: string;
    folders: string[];
    open: boolean;
    onSave: (folder: string, name: string) => void;
    onCancel: () => void;
  }

  let { defaultName, defaultFolder, folders, open, onSave, onCancel }: Props = $props();
  let name = $state<string>(defaultName);
  let folder = $state<string>(defaultFolder);

  $effect(() => {
    if (open) {
      name = defaultName;
      folder = defaultFolder;
    }
  });

  const valid = $derived(name.trim().length > 0);
</script>

<Modal title="Save query" {open} size="md" onClose={onCancel}>
  <label class="field">
    <span class="field__label">Folder</span>
    <select class="field__input" bind:value={folder}>
      {#each folders as f}
        <option value={f}>{f || "/"}</option>
      {/each}
    </select>
  </label>
  <label class="field">
    <span class="field__label">Name</span>
    <input class="field__input" bind:value={name} autocomplete="off" required />
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button
      type="button"
      class="btn btn--primary"
      disabled={!valid}
      onclick={() => onSave(folder, name.trim())}
    >Save</button>
  {/snippet}
</Modal>

<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { lastSegment, validateFolderName } from "$lib/dashboard/catalogueTree";

  /** Rename a single catalogue folder (#937). The parent decides what the
   *  rename means in repo terms (a batch of resource moves); this modal
   *  just collects a validated new leaf name. */
  interface Props {
    folderPath: string;
    open: boolean;
    onRename: (newName: string) => void;
    onCancel: () => void;
  }

  let { folderPath, open, onRename, onCancel }: Props = $props();

  let name = $state<string>(untrack(() => lastSegment(folderPath)));

  // Reset the input to the current folder's leaf name whenever the modal
  // is (re)opened for a different folder.
  $effect(() => {
    if (open) name = lastSegment(folderPath);
  });

  let error = $derived(validateFolderName(name));
  let valid = $derived(error === null && name.trim() !== lastSegment(folderPath));

  function errorMessage(): string | null {
    if (error == null) return null;
    return i18n.t(`dashboard.folder.nameError.${error}`);
  }

  function commit(): void {
    if (valid) onRename(name.trim());
  }
</script>

<Modal title={i18n.t("dashboard.folder.renameTitle")} {open} size="sm" onClose={onCancel}>
  <p class="hint"><code>{folderPath}</code></p>
  <label class="field">
    <span class="field__label">{i18n.t("dashboard.folder.renameLabel")}</span>
    <!-- svelte-ignore a11y_autofocus -->
    <input
      class="field__input"
      bind:value={name}
      autocomplete="off"
      onkeydown={(e) => e.key === "Enter" && commit()}
    />
  </label>
  {#if errorMessage() && name.trim()}
    <p class="err">{errorMessage()}</p>
  {/if}
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button type="button" class="btn btn--primary" disabled={!valid} onclick={commit}>
      {i18n.t("modal.save")}
    </button>
  {/snippet}
</Modal>

<style>
  .hint {
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    margin: 0 0 var(--space-3);
  }
  code {
    background: var(--bg-subtle);
    padding: 0 var(--space-1);
    border-radius: var(--radius-sm);
    font-family: var(--font-mono);
    font-size: 12px;
  }
  .err {
    color: var(--danger);
    font-size: var(--fs-sm);
    margin: var(--space-2) 0 0;
  }
</style>

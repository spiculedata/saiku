<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /*
   * Save-query dialog.
   *
   * The Folder field is a combo input — text + datalist of existing
   * folders. Users can pick from the autocomplete or type a new
   * sub-folder path (e.g. "homes/admin/dashboards") and the backend's
   * saveFile flow mkdirs the parent dirs at write time. The previous
   * <select>-only version forced every save into an existing folder
   * with no way to create new structure from the UI.
   */
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

  /** Trim leading/trailing slashes and collapse repeated separators
   *  before passing the path on to onSave. The backend tolerates either
   *  form but storing the canonical version keeps repository listings
   *  consistent. */
  function normalizeFolder(raw: string): string {
    return raw.replace(/^\/+|\/+$/g, "").replace(/\/{2,}/g, "/");
  }

  const valid = $derived(name.trim().length > 0);

  const datalistId = `save-query-folders-${Math.random().toString(36).slice(2, 9)}`;
</script>

<Modal title={i18n.t("modal.save.title")} {open} size="md" onClose={onCancel}>
  <label class="field">
    <span class="field__label">{i18n.t("modal.save.folder")}</span>
    <input
      class="field__input"
      type="text"
      list={datalistId}
      bind:value={folder}
      placeholder="homes/your-name"
      autocomplete="off"
      spellcheck="false"
    />
    <datalist id={datalistId}>
      {#each folders as f}
        <option value={f}>{f || "/"}</option>
      {/each}
    </datalist>
    <p class="field__hint">{i18n.t("modal.save.folderHint")}</p>
  </label>
  <label class="field">
    <span class="field__label">{i18n.t("modal.save.name")}</span>
    <input class="field__input" bind:value={name} autocomplete="off" required />
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button
      type="button"
      class="btn btn--primary"
      disabled={!valid}
      onclick={() => onSave(normalizeFolder(folder), name.trim())}
    >{i18n.t("modal.save")}</button>
  {/snippet}
</Modal>

<style>
  .field__hint {
    margin: var(--space-1) 0 0;
    color: var(--fg-subtle);
    font-size: var(--fs-xs);
  }
</style>

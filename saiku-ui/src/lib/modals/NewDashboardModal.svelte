<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import RepositoryBrowser from "$lib/components/RepositoryBrowser.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /*
   * "Create a new dashboard" dialog.
   *
   * Replaces two stacked window.prompt() calls (name + path) with a
   * proper modal that mirrors SaveQueryModal's shape — folder picker
   * via RepositoryBrowser, separate name input, filename auto-derived
   * from the name as .saikudash. The previous prompt-based flow looked
   * like a security warning on first use.
   *
   * Folder picker filters to .saikudash files so users see the
   * existing dashboards in each folder; folder click descends.
   */
  interface Props {
    defaultName: string;
    defaultFolder: string;
    open: boolean;
    onCreate: (path: string, name: string) => void;
    onCancel: () => void;
  }

  let { defaultName, defaultFolder, open, onCreate, onCancel }: Props = $props();

  let name = $state<string>(defaultName);
  let folder = $state<string>(defaultFolder);
  let nameTouched = $state<boolean>(false);

  $effect(() => {
    if (open) {
      name = defaultName;
      folder = defaultFolder;
      nameTouched = false;
    }
  });

  function slugify(s: string): string {
    return (
      s
        .toLowerCase()
        .trim()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "")
        .slice(0, 60) || "dashboard"
    );
  }

  function normalizeFolder(raw: string): string {
    return raw.replace(/^\/+|\/+$/g, "").replace(/\/{2,}/g, "/");
  }

  const computedPath = $derived.by(() => {
    const f = normalizeFolder(folder);
    const stem = slugify(name);
    const filename = `${stem}.saikudash`;
    return f ? `${f}/${filename}` : filename;
  });

  const valid = $derived(name.trim().length > 0);

  function commit() {
    if (!valid) return;
    onCreate(computedPath, name.trim());
  }
</script>

<Modal title={i18n.t("dashboard.new.title")} {open} size="lg" onClose={onCancel}>
  <label class="field">
    <span class="field__label">{i18n.t("dashboard.new.name")}</span>
    <input
      class="field__input"
      bind:value={name}
      oninput={() => (nameTouched = true)}
      placeholder="Untitled dashboard"
      autocomplete="off"
    />
  </label>

  <div class="field__label">{i18n.t("dashboard.new.folder")}</div>
  <RepositoryBrowser
    mode="save"
    fileTypes={["saikudash"]}
    selectedPath={folder}
    onSelect={(p) => (folder = p)}
  />

  <p class="path-preview">
    {i18n.t("dashboard.new.willSaveTo")}
    <code>{computedPath}</code>
  </p>

  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button
      type="button"
      class="btn btn--primary"
      disabled={!valid}
      onclick={commit}
    >{i18n.t("dashboard.new.create")}</button>
  {/snippet}
</Modal>

<style>
  .path-preview {
    margin: var(--space-3) 0 0;
    color: var(--fg-muted);
    font-size: var(--fs-sm);
  }
  .path-preview code {
    background: var(--bg-muted);
    padding: 2px 6px;
    border-radius: var(--radius-sm);
    color: var(--fg);
    font-family: var(--font-mono);
    font-size: var(--fs-xs);
  }
</style>

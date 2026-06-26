<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import RepositoryBrowser from "$lib/components/RepositoryBrowser.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { TEMPLATES } from "$lib/dashboard/templates";

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
    /** {@code templateId} is `null` for a blank dashboard or the id of a
     *  starter template from $lib/dashboard/templates (#938). */
    onCreate: (path: string, name: string, templateId: string | null) => void;
    onCancel: () => void;
  }

  let { defaultName, defaultFolder, open, onCreate, onCancel }: Props = $props();

  let name = $state<string>(untrack(() => defaultName));
  let folder = $state<string>(untrack(() => defaultFolder));
  let nameTouched = $state<boolean>(false);
  /** Selected starter template id, or `null` for a blank dashboard (#938). */
  let templateId = $state<string | null>(null);

  $effect(() => {
    if (open) {
      name = defaultName;
      folder = defaultFolder;
      nameTouched = false;
      templateId = null;
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

  function selectTemplate(id: string | null): void {
    templateId = id;
    // When the user picks a template and hasn't renamed the dashboard,
    // default the name to the template's name so the path preview + saved
    // file read sensibly. A blank pick leaves the name untouched.
    if (id && !nameTouched) {
      const t = TEMPLATES.find((x) => x.id === id);
      if (t) name = t.name;
    }
  }

  function commit() {
    if (!valid) return;
    onCreate(computedPath, name.trim(), templateId);
  }
</script>

<Modal title={i18n.t("dashboard.new.title")} {open} size="lg" onClose={onCancel}>
  <div class="field__label">{i18n.t("dashboard.new.startFrom")}</div>
  <div class="templates" role="radiogroup" aria-label={i18n.t("dashboard.new.startFrom")}>
    <button
      type="button"
      class="template-card"
      class:template-card--on={templateId === null}
      role="radio"
      aria-checked={templateId === null}
      onclick={() => selectTemplate(null)}
    >
      <span class="template-card__name">{i18n.t("dashboard.new.blank")}</span>
      <span class="template-card__desc">{i18n.t("dashboard.new.blankDesc")}</span>
    </button>
    {#each TEMPLATES as t (t.id)}
      <button
        type="button"
        class="template-card"
        class:template-card--on={templateId === t.id}
        role="radio"
        aria-checked={templateId === t.id}
        onclick={() => selectTemplate(t.id)}
      >
        <span class="template-card__name">{t.name}</span>
        <span class="template-card__desc">{t.description}</span>
      </button>
    {/each}
  </div>

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
  .templates {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: var(--space-2);
    margin: var(--space-2) 0 var(--space-4);
  }
  .template-card {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    text-align: left;
    padding: var(--space-3);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--bg);
    color: var(--fg);
    cursor: pointer;
  }
  .template-card:hover {
    background: var(--bg-muted);
  }
  .template-card--on {
    border-color: var(--accent);
    box-shadow: 0 0 0 1px var(--accent);
  }
  .template-card__name {
    font-weight: var(--weight-medium);
    font-size: var(--fs-sm);
  }
  .template-card__desc {
    font-size: var(--fs-xs);
    color: var(--fg-muted);
  }
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

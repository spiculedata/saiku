<script lang="ts">
  /*
   * "Create a new app" dialog for the App Builder catalogue.
   *
   * Mirrors NewDashboardModal's shape (folder picker via RepositoryBrowser +
   * separate name input + auto-derived .saikuapp filename) but drops the
   * dashboard-only starter templates. Doubles as the "import from dashboard"
   * confirm step: when {@code importing} is true a badge tells the user the
   * new app will wrap the chosen dashboard's layout as its first page.
   */
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import RepositoryBrowser from "$lib/components/RepositoryBrowser.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { composeAppPath } from "$lib/views/app/appImport";

  interface Props {
    defaultName: string;
    defaultFolder: string;
    open: boolean;
    /** True when the create wraps an imported dashboard layout (shows a badge). */
    importing?: boolean;
    onCreate: (path: string, name: string) => void;
    onCancel: () => void;
  }

  let {
    defaultName,
    defaultFolder,
    open,
    importing = false,
    onCreate,
    onCancel,
  }: Props = $props();

  let name = $state<string>(untrack(() => defaultName));
  let folder = $state<string>(untrack(() => defaultFolder));

  $effect(() => {
    if (open) {
      name = defaultName;
      folder = defaultFolder;
    }
  });

  const computedPath = $derived(composeAppPath(folder, name));
  const valid = $derived(name.trim().length > 0);

  function commit(): void {
    if (!valid) return;
    onCreate(computedPath, name.trim());
  }
</script>

<Modal title={importing ? "New app from dashboard" : "New app"} {open} size="lg" onClose={onCancel}>
  {#if importing}
    <p class="import-badge">
      This app will wrap the selected dashboard's layout as its first page. You
      can add more pages after it opens.
    </p>
  {/if}

  <label class="field">
    <span class="field__label">Name</span>
    <input
      class="field__input"
      bind:value={name}
      placeholder="Untitled app"
      autocomplete="off"
    />
  </label>

  <div class="field__label">Folder</div>
  <RepositoryBrowser
    mode="save"
    fileTypes={["saikuapp"]}
    selectedPath={folder}
    onSelect={(p) => (folder = p)}
  />

  <p class="path-preview">
    Will save to
    <code>{computedPath}</code>
  </p>

  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button onclick={commit} disabled={!valid}>Create</Button>
  {/snippet}
</Modal>

<style>
  .import-badge {
    margin: 0 0 var(--space-3);
    padding: var(--space-2) var(--space-3);
    background: hsl(var(--bg-muted));
    border: 1px solid hsl(var(--border));
    border-radius: var(--radius-sm);
    color: hsl(var(--fg-muted));
    font-size: var(--fs-sm);
  }
  .path-preview {
    margin: var(--space-3) 0 0;
    color: hsl(var(--fg-muted));
    font-size: var(--fs-sm);
  }
  .path-preview code {
    background: hsl(var(--bg-muted));
    padding: 2px 6px;
    border-radius: var(--radius-sm);
    color: hsl(var(--fg));
    font-family: var(--font-mono);
    font-size: var(--fs-xs);
  }
</style>

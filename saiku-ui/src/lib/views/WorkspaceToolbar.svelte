<script lang="ts">
  /**
   * Legacy parity target: saiku-ui-legacy/js/saiku/views/WorkspaceToolbar.js
   * + QueryToolbar.js. Actions partially wired in this slice — save/open
   * go through repository store + modals; run/export still stubbed pending
   * query execution slice.
   */

  import SaveQueryModal from "$lib/modals/SaveQueryModal.svelte";
  import OpenDialogModal, { type RepoEntry } from "$lib/modals/OpenDialogModal.svelte";
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";
  import WarningModal from "$lib/modals/WarningModal.svelte";
  import { repository } from "$lib/stores/repository.svelte";
  import { toasts } from "$lib/stores/toasts.svelte";

  let autorun = $state(true);
  let nonEmpty = $state(true);
  let dirty = $state(false);

  let saveOpen = $state(false);
  let openOpen = $state(false);
  let confirmNewOpen = $state(false);
  let warningOpen = $state(false);
  let warningMessage = $state("");

  async function ensureRepoLoaded() {
    if (!repository.loaded && !repository.loading) {
      await repository.refresh();
    }
  }

  function onNew() {
    if (dirty) {
      confirmNewOpen = true;
    } else {
      resetQuery();
    }
  }

  function resetQuery() {
    dirty = false;
    toasts.info("New query", "A fresh query workspace has been opened.");
  }

  async function onOpen() {
    await ensureRepoLoaded();
    openOpen = true;
  }

  async function onSave() {
    await ensureRepoLoaded();
    saveOpen = true;
  }

  function onRun() {
    warningMessage = "Query execution wires into /rest/saiku/api/query in the next Phase 4 slice.";
    warningOpen = true;
  }

  function stubExport(kind: string) {
    toasts.info(`Export (${kind})`, "Export handlers land in the export slice.");
  }

  function onSavePick(folder: string, name: string) {
    saveOpen = false;
    toasts.success("Saved (stub)", `${folder || "/"}/${name}`);
    // Real save wires into query.saveAs() once query execution ships.
    dirty = false;
  }

  function onOpenPick(entry: RepoEntry) {
    openOpen = false;
    toasts.info("Opened (stub)", entry.path);
  }
</script>

<div class="toolbar" role="toolbar" aria-label="Workspace toolbar">
  <div class="toolbar__group">
    <button class="btn" title="New query" onclick={onNew}>＋ New</button>
    <button class="btn" title="Open query" onclick={onOpen}>📂 Open</button>
    <button class="btn" title="Save" onclick={onSave}>💾 Save</button>
    <button class="btn" title="Save As" onclick={onSave}>Save As…</button>
  </div>
  <div class="toolbar__sep"></div>
  <div class="toolbar__group">
    <button class="btn btn--primary" title="Run query" onclick={onRun}>▶ Run</button>
    <label class="toolbar__toggle">
      <input type="checkbox" bind:checked={autorun} /> Autorun
    </label>
    <label class="toolbar__toggle">
      <input type="checkbox" bind:checked={nonEmpty} /> Non-empty
    </label>
  </div>
  <div class="toolbar__sep"></div>
  <div class="toolbar__group">
    <button class="btn" title="Swap axes" onclick={() => toasts.info("Swap axes", "Handler wires in query execution slice.")}>⇄ Swap</button>
    <button class="btn" title="Show MDX" onclick={() => toasts.info("MDX editor", "Monaco-backed MDX modal ships in the MDX slice.")}>MDX</button>
  </div>
  <div class="toolbar__spacer"></div>
  <div class="toolbar__group">
    <button class="btn" title="Export XLS" onclick={() => stubExport("XLS")}>XLS</button>
    <button class="btn" title="Export CSV" onclick={() => stubExport("CSV")}>CSV</button>
    <button class="btn" title="Export PDF" onclick={() => stubExport("PDF")}>PDF</button>
  </div>
</div>

<SaveQueryModal
  defaultName="Untitled query"
  defaultFolder=""
  folders={repository.folders}
  open={saveOpen}
  onSave={onSavePick}
  onCancel={() => (saveOpen = false)}
/>

<OpenDialogModal
  entries={repository.flat.map((n) => ({
    path: n.path,
    name: n.name,
    type: n.type === "FOLDER" ? "folder" : "file",
    fileType: n.fileType ?? undefined,
  }))}
  loading={repository.loading}
  open={openOpen}
  onSelect={onOpenPick}
  onCancel={() => (openOpen = false)}
/>

<ConfirmModal
  title="Discard unsaved changes?"
  message="Starting a new query will discard your unsaved work."
  confirmLabel="Discard"
  cancelLabel="Keep editing"
  variant="danger"
  open={confirmNewOpen}
  onConfirm={() => {
    confirmNewOpen = false;
    resetQuery();
  }}
  onCancel={() => (confirmNewOpen = false)}
/>

<WarningModal
  title="Not yet wired"
  message={warningMessage}
  open={warningOpen}
  onClose={() => (warningOpen = false)}
/>

<style>
  .toolbar {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
    flex-wrap: wrap;
  }
  .toolbar__group {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .toolbar__sep {
    width: 1px;
    height: 20px;
    background: var(--border);
  }
  .toolbar__spacer { flex: 1; }
  .toolbar__toggle {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-1) var(--space-2);
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    cursor: pointer;
  }
  .toolbar__toggle input { cursor: pointer; }
</style>

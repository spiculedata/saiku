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
  import MDXModal from "$lib/modals/MDXModal.svelte";
  import { repository } from "$lib/stores/repository.svelte";
  import { getResource, saveResource } from "$lib/api/repository";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { query } from "$lib/stores/query.svelte";

  let autorun = $state(true);
  let nonEmpty = $state(true);

  $effect(() => {
    if (query.current?.queryModel) {
      query.current.queryModel.axes.ROWS.nonEmpty = nonEmpty;
      query.current.queryModel.axes.COLUMNS.nonEmpty = nonEmpty;
    }
  });

  let saveOpen = $state(false);
  let saveAsMode = $state(false);
  let openOpen = $state(false);
  let confirmNewOpen = $state(false);
  let warningOpen = $state(false);
  let warningMessage = $state("");
  let mdxOpen = $state(false);

  function deriveDefaults(): { folder: string; name: string } {
    const path = query.savedPath ?? "";
    if (!path) return { folder: "", name: "Untitled.saiku" };
    const idx = path.lastIndexOf("/");
    const folder = idx > 0 ? path.slice(0, idx) : "";
    const name = idx >= 0 ? path.slice(idx + 1) : path;
    return { folder, name };
  }

  async function ensureRepoLoaded() {
    if (!repository.loaded && !repository.loading) {
      await repository.refresh();
    }
  }

  function onNew() {
    if (query.dirty) {
      confirmNewOpen = true;
    } else {
      resetQuery();
    }
  }

  function resetQuery() {
    const cube = query.current?.cube ?? null;
    query.reset();
    if (cube) query.initFor(cube);
    toasts.info("New query", "A fresh query workspace has been opened.");
  }

  async function onOpen() {
    await ensureRepoLoaded();
    openOpen = true;
  }

  async function onSave() {
    if (!query.current) {
      warningMessage = "Select a cube first.";
      warningOpen = true;
      return;
    }
    await ensureRepoLoaded();
    if (query.savedPath) {
      try {
        await saveResource(query.savedPath, JSON.stringify(query.current));
        query.markSaved(query.savedPath);
        toasts.success("Saved", query.savedPath);
      } catch (e) {
        toasts.danger("Save failed", e instanceof Error ? e.message : String(e));
      }
      return;
    }
    saveAsMode = false;
    saveOpen = true;
  }

  async function onSaveAs() {
    if (!query.current) {
      warningMessage = "Select a cube first.";
      warningOpen = true;
      return;
    }
    await ensureRepoLoaded();
    saveAsMode = true;
    saveOpen = true;
  }

  function onShowMdx() {
    if (!query.current?.mdx && !query.result) {
      toasts.info("No MDX yet", "Run the query first to see its generated MDX.");
      return;
    }
    mdxOpen = true;
  }

  async function onRun() {
    if (!query.current) {
      warningMessage = "Select a cube before running a query.";
      warningOpen = true;
      return;
    }
    await query.run();
  }

  function exportCurrent(kind: "xls" | "csv" | "pdf") {
    if (!query.current) {
      warningMessage = "Run a query first before exporting.";
      warningOpen = true;
      return;
    }
    const name = encodeURIComponent(query.current.name);
    const url = `/rest/saiku/api/query/${name}/export/${kind}`;
    window.open(url, "_blank");
  }

  async function onSavePick(folder: string, name: string) {
    saveOpen = false;
    if (!query.current) return;
    const filename = name.endsWith(".saiku") ? name : `${name}.saiku`;
    const path = folder ? `${folder}/${filename}` : filename;
    try {
      await saveResource(path, JSON.stringify(query.current));
      query.markSaved(path);
      toasts.success("Saved", path);
      await repository.refresh();
    } catch (e) {
      toasts.danger("Save failed", e instanceof Error ? e.message : String(e));
    }
  }

  async function onOpenPick(entry: RepoEntry) {
    openOpen = false;
    if (entry.type !== "file") return;
    try {
      const body = await getResource(entry.path);
      query.loadFromJson(body, entry.path);
      toasts.success("Opened", entry.path);
      if (autorun) await query.run();
    } catch (e) {
      toasts.danger("Open failed", e instanceof Error ? e.message : String(e));
    }
  }

  async function onRunMdx(mdx: string) {
    if (!query.current) return;
    query.current.mdx = mdx;
    query.current.type = "MDX";
    mdxOpen = false;
    await query.run();
  }
</script>

<div class="toolbar" role="toolbar" aria-label="Workspace toolbar">
  <div class="toolbar__group">
    <button class="btn" title="New query" onclick={onNew}>＋ New</button>
    <button class="btn" title="Open query" onclick={onOpen}>📂 Open</button>
    <button class="btn" title="Save" onclick={onSave}>💾 Save</button>
    <button class="btn" title="Save As" onclick={onSaveAs}>Save As…</button>
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
    <button class="btn" title="Swap axes" onclick={() => { query.swapAxes(); if (autorun) query.run(); }}>⇄ Swap</button>
    <button class="btn" title="Show MDX" onclick={onShowMdx}>MDX</button>
  </div>
  <div class="toolbar__spacer"></div>
  <div class="toolbar__group">
    <button class="btn" title="Export XLS" onclick={() => exportCurrent("xls")}>XLS</button>
    <button class="btn" title="Export CSV" onclick={() => exportCurrent("csv")}>CSV</button>
    <button class="btn" title="Export PDF" onclick={() => exportCurrent("pdf")}>PDF</button>
  </div>
</div>

{#if saveOpen}
  {@const d = deriveDefaults()}
  <SaveQueryModal
    defaultName={saveAsMode ? `Copy of ${d.name}` : d.name}
    defaultFolder={d.folder}
    folders={repository.folders}
    open={saveOpen}
    onSave={onSavePick}
    onCancel={() => (saveOpen = false)}
  />
{/if}

<MDXModal
  mdx={query.current?.mdx ?? query.result?.query?.mdx ?? ""}
  open={mdxOpen}
  onRun={onRunMdx}
  onCancel={() => (mdxOpen = false)}
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

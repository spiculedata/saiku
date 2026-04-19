<script lang="ts">
  import SaveQueryModal from "$lib/modals/SaveQueryModal.svelte";
  import OpenDialogModal, { type RepoEntry } from "$lib/modals/OpenDialogModal.svelte";
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";
  import WarningModal from "$lib/modals/WarningModal.svelte";
  import MDXModal from "$lib/modals/MDXModal.svelte";
  import DrillAcrossModal from "$lib/modals/DrillAcrossModal.svelte";
  import ReportTitlesModal, { type ReportTitles } from "$lib/modals/ReportTitlesModal.svelte";
  import { repository } from "$lib/stores/repository.svelte";
  import { getResource, saveResource } from "$lib/api/repository";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { query } from "$lib/stores/query.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import { datasources } from "$lib/stores/datasources.svelte";
  import type { SaikuCube } from "$lib/api/discover";
  import { i18n } from "$lib/stores/i18n.svelte";

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
  let drillAcrossOpen = $state(false);
  let reportTitlesOpen = $state(false);
  let toolsMenuOpen = $state(false);
  let exportMenuOpen = $state(false);

  let reportTitles = $state<ReportTitles>({ title: "", subtitle: "", notes: "" });

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
    if (query.dirty) confirmNewOpen = true;
    else resetQuery();
  }

  function resetQuery() {
    const cube = query.current?.cube ?? null;
    query.reset();
    if (cube) query.initFor(cube);
    toasts.info("New query", "A fresh query workspace has been opened.");
  }

  async function onOpen() { await ensureRepoLoaded(); openOpen = true; }

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
    exportMenuOpen = false;
    if (!query.current) {
      warningMessage = "Run a query first before exporting.";
      warningOpen = true;
      return;
    }
    const name = encodeURIComponent(query.current.name);
    window.open(`/rest/saiku/api/query/${name}/export/${kind}`, "_blank");
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
      if (query.autorun) await query.run();
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

  function closeToolsMenu() { toolsMenuOpen = false; }
  function closeExportMenu() { exportMenuOpen = false; }

  function openDrillAcross() {
    closeToolsMenu();
    drillAcrossOpen = true;
  }

  function openReportTitles() {
    closeToolsMenu();
    reportTitlesOpen = true;
  }

  async function onDrillAcross(target: SaikuCube) {
    drillAcrossOpen = false;
    selection.select(target);
    query.initFor(target);
    toasts.info("Drill across", `Switched cube to ${target.caption || target.name}`);
  }

  function drillAcrossTargets(): SaikuCube[] {
    const cube = selection.cube;
    if (!cube) return [];
    const cubes: SaikuCube[] = [];
    for (const conn of datasources.connections) {
      for (const cat of conn.catalogs ?? []) {
        for (const sch of cat.schemas ?? []) {
          for (const c of sch.cubes ?? []) {
            if (c.uniqueName !== cube.uniqueName) cubes.push(c);
          }
        }
      }
    }
    return cubes;
  }

  function onReportTitlesSave(t: ReportTitles) {
    reportTitles = t;
    reportTitlesOpen = false;
    if (query.current) {
      query.current.properties = {
        ...(query.current.properties ?? {}),
        "saiku.report.title": t.title,
        "saiku.report.subtitle": t.subtitle,
        "saiku.report.notes": t.notes,
      };
    }
    toasts.success("Titles saved", t.title || "(cleared)");
  }

  function handleBodyClick(e: MouseEvent) {
    const t = e.target as Element | null;
    if (t?.closest(".toolbar__menu")) return;
    toolsMenuOpen = false;
    exportMenuOpen = false;
  }

  $effect(() => {
    // Seed the report titles panel from the current query on load.
    const p = query.current?.properties ?? {};
    reportTitles = {
      title: String(p["saiku.report.title"] ?? ""),
      subtitle: String(p["saiku.report.subtitle"] ?? ""),
      notes: String(p["saiku.report.notes"] ?? ""),
    };
  });
</script>

<svelte:window onclick={handleBodyClick} />

<div class="toolbar" role="toolbar" aria-label="Workspace toolbar">
  <div class="toolbar__group">
    <button class="btn btn--icon" onclick={onNew}>{i18n.t("toolbar.new")}</button>
    <button class="btn btn--icon" onclick={onOpen}>{i18n.t("toolbar.open")}</button>
    <button class="btn btn--icon" onclick={onSave}>{i18n.t("toolbar.save")}</button>
    <button class="btn btn--icon" onclick={onSaveAs}>{i18n.t("toolbar.saveAs")}</button>
  </div>
  <div class="toolbar__sep"></div>
  <div class="toolbar__group">
    <button class="btn btn--primary btn--icon" onclick={onRun}>{i18n.t("toolbar.run")}</button>
    <label class="toolbar__toggle">
      <input type="checkbox" bind:checked={query.autorun} /> {i18n.t("toolbar.autorun")}
    </label>
    <label class="toolbar__toggle">
      <input type="checkbox" bind:checked={nonEmpty} /> {i18n.t("toolbar.nonEmpty")}
    </label>
    <button class="btn btn--icon" onclick={() => query.swapAxes()}>{i18n.t("toolbar.swap")}</button>
  </div>
  <div class="toolbar__sep"></div>
  <div class="toolbar__group toolbar__menu">
    <button
      class="btn btn--icon"
      onclick={(e) => { e.stopPropagation(); toolsMenuOpen = !toolsMenuOpen; exportMenuOpen = false; }}
    >Tools ▾</button>
    {#if toolsMenuOpen}
      <div class="toolbar__dropdown">
        <button type="button" class="toolbar__item" onclick={onShowMdx}>{i18n.t("toolbar.mdx")}…</button>
        <button type="button" class="toolbar__item" onclick={openDrillAcross}>Drill across…</button>
        <button type="button" class="toolbar__item" onclick={openReportTitles}>Report titles…</button>
      </div>
    {/if}
  </div>
  <div class="toolbar__spacer"></div>
  <div class="toolbar__group toolbar__menu">
    <button
      class="btn btn--icon"
      onclick={(e) => { e.stopPropagation(); exportMenuOpen = !exportMenuOpen; toolsMenuOpen = false; }}
    >⬇ Export ▾</button>
    {#if exportMenuOpen}
      <div class="toolbar__dropdown toolbar__dropdown--right">
        <button type="button" class="toolbar__item" onclick={() => exportCurrent("xls")}>📊 {i18n.t("toolbar.export.xls")}</button>
        <button type="button" class="toolbar__item" onclick={() => exportCurrent("csv")}>📄 {i18n.t("toolbar.export.csv")}</button>
        <button type="button" class="toolbar__item" onclick={() => exportCurrent("pdf")}>📕 {i18n.t("toolbar.export.pdf")}</button>
      </div>
    {/if}
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

<DrillAcrossModal
  targets={drillAcrossTargets()}
  open={drillAcrossOpen}
  onRun={onDrillAcross}
  onCancel={() => (drillAcrossOpen = false)}
/>

<ReportTitlesModal
  titles={reportTitles}
  open={reportTitlesOpen}
  onSave={onReportTitlesSave}
  onCancel={() => (reportTitlesOpen = false)}
/>

<ConfirmModal
  title="Discard unsaved changes?"
  message="Starting a new query will discard your unsaved work."
  confirmLabel="Discard"
  cancelLabel="Keep editing"
  variant="danger"
  open={confirmNewOpen}
  onConfirm={() => { confirmNewOpen = false; resetQuery(); }}
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
    padding: 6px var(--space-3);
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
    flex-wrap: wrap;
  }
  .toolbar__group {
    display: flex;
    align-items: center;
    gap: 4px;
    position: relative;
  }
  .toolbar__menu { position: relative; }
  .toolbar__dropdown {
    position: absolute;
    top: calc(100% + 4px);
    left: 0;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    box-shadow: 0 10px 24px rgba(0,0,0,0.4);
    padding: 4px 0;
    z-index: 50;
    min-width: 200px;
  }
  .toolbar__dropdown--right { left: auto; right: 0; }
  .toolbar__item {
    display: block;
    width: 100%;
    text-align: left;
    padding: 6px var(--space-3);
    background: transparent;
    border: 0;
    color: var(--fg);
    font: inherit;
    cursor: pointer;
  }
  .toolbar__item:hover { background: var(--bg-subtle); }
  .toolbar__sep {
    width: 1px;
    height: 22px;
    background: var(--border);
  }
  .toolbar__spacer { flex: 1; }
  .toolbar__toggle {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: 2px 6px;
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    cursor: pointer;
    user-select: none;
  }
  .toolbar__toggle input { cursor: pointer; }
  .btn--icon {
    padding: 4px 10px;
    line-height: 1.2;
  }
</style>

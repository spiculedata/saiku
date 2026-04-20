<script lang="ts">
  import {
    FilePlus2,
    FolderOpen,
    Save,
    Copy,
    Play,
    ArrowLeftRight,
    Wrench,
    Download,
    FileSpreadsheet,
    FileText,
    FileType,
    ChevronDown,
    Braces,
    Sigma,
    Tags,
  } from "lucide-svelte";
  import SaveQueryModal from "$lib/modals/SaveQueryModal.svelte";
  import SavedQueriesModal from "$lib/modals/SavedQueriesModal.svelte";
  import type { ThinQuery } from "$lib/api/query";
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";
  import WarningModal from "$lib/modals/WarningModal.svelte";
  import MDXModal from "$lib/modals/MDXModal.svelte";
  import DrillAcrossModal from "$lib/modals/DrillAcrossModal.svelte";
  import ReportTitlesModal, { type ReportTitles } from "$lib/modals/ReportTitlesModal.svelte";
  import { repository } from "$lib/stores/repository.svelte";
  import { saveResource } from "$lib/api/repository";
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
  let runMenuOpen = $state(false);

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

  async function onOpenQuery(path: string, q: ThinQuery) {
    openOpen = false;
    try {
      // Sync the selection store so the sidebar reflects the cube of the
      // loaded query — otherwise DimensionList stays on the old cube.
      if (q.cube) selection.select(q.cube);
      query.hydrate(q, path);
      toasts.success("Opened", path);
      if (query.autorun && query.hasRunnableShape()) await query.run();
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
    runMenuOpen = false;
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
  <div class="toolbar__group" role="group" aria-label="File">
    <button class="tb-btn" title={i18n.t("toolbar.new")} aria-label={i18n.t("toolbar.new")} onclick={onNew}>
      <FilePlus2 size={18} />
    </button>
    <button class="tb-btn" title={i18n.t("toolbar.open")} aria-label={i18n.t("toolbar.open")} onclick={onOpen}>
      <FolderOpen size={18} />
    </button>
    <button
      class="tb-btn tb-btn--dirty"
      title={query.dirty ? `${i18n.t("toolbar.save")} (unsaved changes)` : i18n.t("toolbar.save")}
      aria-label={i18n.t("toolbar.save")}
      onclick={onSave}
    >
      <Save size={18} />
      {#if query.dirty}
        <span class="tb-btn__dot" aria-hidden="true"></span>
      {/if}
    </button>
    <button class="tb-btn" title={i18n.t("toolbar.saveAs")} aria-label={i18n.t("toolbar.saveAs")} onclick={onSaveAs}>
      <Copy size={18} />
    </button>
  </div>
  <div class="toolbar__sep"></div>
  <div class="toolbar__group toolbar__menu" role="group" aria-label="Query">
    <div class="split-btn">
      <button class="tb-btn tb-btn--primary split-btn__main" title={i18n.t("toolbar.run")} onclick={onRun}>
        <Play size={18} /><span class="tb-btn__label">{i18n.t("toolbar.run")}</span>
      </button>
      <button
        class="tb-btn tb-btn--primary split-btn__caret"
        title="Run options"
        aria-label="Run options"
        onclick={(e) => { e.stopPropagation(); runMenuOpen = !runMenuOpen; toolsMenuOpen = false; exportMenuOpen = false; }}
      ><ChevronDown size={14} /></button>
    </div>
    {#if runMenuOpen}
      <div class="toolbar__dropdown">
        <label class="toolbar__check" title="Automatically run queries after each edit">
          <input type="checkbox" bind:checked={query.autorun} />
          <span>{i18n.t("toolbar.autorun")}</span>
        </label>
        <label class="toolbar__check" title="Hide empty rows and columns">
          <input type="checkbox" bind:checked={nonEmpty} />
          <span>{i18n.t("toolbar.nonEmpty")}</span>
        </label>
        <label class="toolbar__check" title="Submit queries asynchronously with progress + cancel">
          <input type="checkbox" bind:checked={query.async} />
          <span>{i18n.t("toolbar.async")}</span>
        </label>
      </div>
    {/if}
    <button class="tb-btn" title={i18n.t("toolbar.swap")} aria-label={i18n.t("toolbar.swap")} onclick={() => query.swapAxes()}>
      <ArrowLeftRight size={18} />
    </button>
  </div>
  <div class="toolbar__sep"></div>
  <div class="toolbar__group toolbar__menu" role="group" aria-label="Tools">
    <button
      class="tb-btn"
      onclick={(e) => { e.stopPropagation(); toolsMenuOpen = !toolsMenuOpen; exportMenuOpen = false; }}
      title="Tools"
    >
      <Wrench size={18} /><span class="tb-btn__label">Tools</span><ChevronDown size={14} />
    </button>
    {#if toolsMenuOpen}
      <div class="toolbar__dropdown">
        <button type="button" class="toolbar__item" onclick={onShowMdx}>
          <Braces size={16} /> <span>{i18n.t("toolbar.mdx")}…</span>
        </button>
        <button type="button" class="toolbar__item" onclick={openDrillAcross}>
          <ArrowLeftRight size={16} /> <span>Drill across…</span>
        </button>
        <button type="button" class="toolbar__item" onclick={openReportTitles}>
          <Tags size={16} /> <span>Report titles…</span>
        </button>
      </div>
    {/if}
  </div>
  <div class="toolbar__spacer"></div>
  <div class="toolbar__group toolbar__menu" role="group" aria-label="Export">
    <button
      class="tb-btn"
      onclick={(e) => { e.stopPropagation(); exportMenuOpen = !exportMenuOpen; toolsMenuOpen = false; }}
      title="Export"
    >
      <Download size={18} /><span class="tb-btn__label">Export</span><ChevronDown size={14} />
    </button>
    {#if exportMenuOpen}
      <div class="toolbar__dropdown toolbar__dropdown--right">
        <button type="button" class="toolbar__item" onclick={() => exportCurrent("xls")}>
          <FileSpreadsheet size={16} /> <span>{i18n.t("toolbar.export.xls")}</span>
        </button>
        <button type="button" class="toolbar__item" onclick={() => exportCurrent("csv")}>
          <FileText size={16} /> <span>{i18n.t("toolbar.export.csv")}</span>
        </button>
        <button type="button" class="toolbar__item" onclick={() => exportCurrent("pdf")}>
          <FileType size={16} /> <span>{i18n.t("toolbar.export.pdf")}</span>
        </button>
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

<SavedQueriesModal
  open={openOpen}
  onOpenQuery={onOpenQuery}
  onClose={() => (openOpen = false)}
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
    gap: 6px;
    padding: 6px 10px;
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
    flex-wrap: wrap;
  }
  .toolbar__group {
    display: flex;
    align-items: center;
    gap: 2px;
    position: relative;
  }
  .toolbar__menu { position: relative; }
  .toolbar__dropdown {
    position: absolute;
    top: calc(100% + 6px);
    left: 0;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    box-shadow: 0 12px 32px rgba(0,0,0,0.35);
    padding: 4px;
    z-index: 50;
    min-width: 220px;
  }
  .toolbar__dropdown--right { left: auto; right: 0; }
  .toolbar__item {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    text-align: left;
    padding: 7px 12px;
    background: transparent;
    border: 0;
    border-radius: 4px;
    color: var(--fg);
    font: inherit;
    cursor: pointer;
  }
  .toolbar__item:hover { background: var(--bg-subtle); }
  .toolbar__sep {
    width: 1px;
    height: 22px;
    background: var(--border);
    margin: 0 4px;
  }
  .toolbar__spacer { flex: 1; }
  .toolbar__toggle {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 4px 8px;
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    cursor: pointer;
    user-select: none;
    border-radius: 4px;
  }
  .toolbar__toggle:hover { background: var(--bg-subtle); color: var(--fg); }
  .toolbar__toggle input { cursor: pointer; }

  .tb-btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 10px;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 5px;
    color: var(--fg-muted);
    cursor: pointer;
    font: inherit;
  }
  .tb-btn:hover { background: var(--bg-subtle); color: var(--fg); }
  .tb-btn:active { transform: translateY(1px); }
  .tb-btn__label { font-size: var(--fs-sm); }
  .tb-btn--primary {
    background: var(--accent);
    color: var(--bg);
    border-color: var(--accent);
  }
  .tb-btn--primary:hover { filter: brightness(1.1); background: var(--accent); color: var(--bg); }
  .tb-btn--dirty { position: relative; }
  .tb-btn__dot {
    position: absolute;
    top: 4px;
    right: 4px;
    width: 7px;
    height: 7px;
    background: var(--accent);
    border-radius: 50%;
    box-shadow: 0 0 0 2px var(--bg-muted);
  }

  .split-btn {
    display: inline-flex;
    align-items: stretch;
    border-radius: 5px;
    overflow: hidden;
  }
  .split-btn__main {
    border-top-right-radius: 0;
    border-bottom-right-radius: 0;
    border-right: 1px solid rgba(0,0,0,0.25);
  }
  .split-btn__caret {
    padding: 6px 6px;
    border-top-left-radius: 0;
    border-bottom-left-radius: 0;
  }
  .toolbar__check {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 12px;
    cursor: pointer;
    color: var(--fg);
    font: inherit;
    border-radius: 4px;
  }
  .toolbar__check:hover { background: var(--bg-subtle); }
</style>

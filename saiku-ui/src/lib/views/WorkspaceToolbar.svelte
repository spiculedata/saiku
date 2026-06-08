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
    Search,
    Maximize2,
    Minimize2,
    Sparkles,
  } from "lucide-svelte";
  import SaveQueryModal from "$lib/modals/SaveQueryModal.svelte";
  import SavedQueriesModal from "$lib/modals/SavedQueriesModal.svelte";
  import { getQueryMdx, type ThinQuery } from "$lib/api/query";
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
  import { platform } from "$lib/stores/platform.svelte";

  interface Props {
    /** Open the AI Query drawer. Owned by Workspace so the drawer can overlay the canvas. */
    onAskAi?: () => void;
  }

  let { onAskAi }: Props = $props();

  let nonEmpty = $state(true);
  let visualTotals = $state(false);

  $effect(() => {
    if (query.current?.queryModel) {
      query.current.queryModel.axes.ROWS.nonEmpty = nonEmpty;
      query.current.queryModel.axes.COLUMNS.nonEmpty = nonEmpty;
    }
  });

  /**
   * saiku-cloud#612 — derived runnable state for the Run button.
   *
   * Mirrors `query.hasRunnableShape()` but in a $derived form so
   * the Run button can be visually disabled + tooltipped when the
   * shape isn't runnable. Previously the Run button stayed primary-
   * coloured and clickable; the empty-measure failure only surfaced
   * AFTER click, inline in the result pane (easy to miss for a
   * customer used to other BI tools that prevent the click).
   *
   * Returns:
   *   - { ok: true } when the query is runnable
   *   - { ok: false, reason: <i18n-key>, hint: <i18n-key> } when not
   *
   * The two not-runnable reasons we surface explicitly: missing
   * measure (most common — drag a measure into the Measures area)
   * and missing hierarchy on rows + columns. The query-store's
   * post-click error fallback still catches anything else.
   */
  let runnable = $derived.by((): { ok: true } | { ok: false; reasonKey: string; hintKey: string } => {
    const q = query.current?.queryModel;
    if (!q) return { ok: false, reasonKey: "toolbar.run.disabled.noQuery", hintKey: "toolbar.run.disabled.noQuery.hint" };
    if (q.details.measures.length === 0) {
      return { ok: false, reasonKey: "toolbar.run.disabled.noMeasure", hintKey: "toolbar.run.disabled.noMeasure.hint" };
    }
    const hasHierarchy = q.axes.COLUMNS.hierarchies.length > 0 || q.axes.ROWS.hierarchies.length > 0;
    if (!hasHierarchy) {
      return { ok: false, reasonKey: "toolbar.run.disabled.noHierarchy", hintKey: "toolbar.run.disabled.noHierarchy.hint" };
    }
    return { ok: true };
  });

  $effect(() => {
    if (query.current?.queryModel) {
      query.current.queryModel.visualTotals = visualTotals;
    }
  });

  let saveOpen = $state(false);
  let saveAsMode = $state(false);
  let openOpen = $state(false);
  let confirmNewOpen = $state(false);
  let warningOpen = $state(false);
  let warningMessage = $state("");
  let mdxOpen = $state(false);
  let mdxBuffer = $state<string>("");
  let drillAcrossOpen = $state(false);
  let reportTitlesOpen = $state(false);
  let toolsMenuOpen = $state(false);
  let exportMenuOpen = $state(false);
  let runMenuOpen = $state(false);

  let reportTitles = $state<ReportTitles>({ title: "", subtitle: "", notes: "" });

  function defaultHomeFolder(): string {
    // Anchor first-time saves to the user's home so they don't accidentally
    // pick repository-root (saiku#878 follow-up known issue) or whichever
    // folder happens to be first alphabetically in the dropdown. Falls back
    // to plain "homes" if we somehow don't have a session yet.
    const u = session.current?.username;
    return u ? `homes/${u}` : "homes";
  }

  function deriveDefaults(): { folder: string; name: string } {
    const path = query.savedPath ?? "";
    if (!path) return { folder: defaultHomeFolder(), name: "Untitled.saiku" };
    const idx = path.lastIndexOf("/");
    const folder = idx > 0 ? path.slice(0, idx) : defaultHomeFolder();
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
    toasts.info(i18n.t("toast.newQuery"), i18n.t("toast.newQuery.body"));
  }

  async function onOpen() { await ensureRepoLoaded(); openOpen = true; }

  async function onSave() {
    if (!query.current) {
      warningMessage = i18n.t("warning.selectCube");
      warningOpen = true;
      return;
    }
    await ensureRepoLoaded();
    if (query.savedPath) {
      try {
        await saveResource(query.savedPath, JSON.stringify(query.current));
        query.markSaved(query.savedPath);
        toasts.success(i18n.t("toast.saved"), query.savedPath);
      } catch (e) {
        toasts.danger(i18n.t("toast.saveFailed"), e instanceof Error ? e.message : String(e));
      }
      return;
    }
    saveAsMode = false;
    saveOpen = true;
  }

  async function onSaveAs() {
    if (!query.current) {
      warningMessage = i18n.t("warning.selectCube");
      warningOpen = true;
      return;
    }
    await ensureRepoLoaded();
    saveAsMode = true;
    saveOpen = true;
  }

  async function onShowMdx() {
    const local = query.current?.mdx ?? query.result?.query?.mdx ?? "";
    // Prefer fresh MDX from the server — it reflects whatever the engine
    // generated for the latest run. Falls back to whatever the client
    // already knows (works for unregistered / unrun queries that the
    // user has manually typed in via this modal).
    let fromServer: string | null = null;
    if (query.current?.name) {
      try {
        fromServer = await getQueryMdx(query.current.name);
      } catch {
        fromServer = null;
      }
    }
    const next = (fromServer ?? local ?? "").trim();
    if (!next) {
      toasts.info(i18n.t("toast.noMdx"), i18n.t("toast.noMdx.body"));
      return;
    }
    mdxBuffer = next;
    mdxOpen = true;
  }

  function openDrillthrough() {
    toolsMenuOpen = false;
    if (!query.current || !query.result) {
      toasts.info(i18n.t("toast.noDrill"), i18n.t("toast.noDrill.body"));
      return;
    }
    window.dispatchEvent(new CustomEvent("saiku-open-drillthrough"));
  }

  async function onRun() {
    if (!query.current) {
      warningMessage = i18n.t("warning.selectCubeToRun");
      warningOpen = true;
      return;
    }
    await query.run();
  }

  function exportCurrent(kind: "xls" | "csv" | "pdf") {
    exportMenuOpen = false;
    if (!query.current) {
      warningMessage = i18n.t("warning.runBeforeExport");
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
      toasts.success(i18n.t("toast.saved"), path);
      await repository.refresh();
    } catch (e) {
      toasts.danger(i18n.t("toast.saveFailed"), e instanceof Error ? e.message : String(e));
    }
  }

  async function onOpenQuery(path: string, q: ThinQuery) {
    openOpen = false;
    try {
      // Sync the selection store so the sidebar reflects the cube of the
      // loaded query — otherwise DimensionList stays on the old cube.
      if (q.cube) selection.select(q.cube);
      query.hydrate(q, path);
      toasts.success(i18n.t("toast.opened"), path);
      if (query.autorun && query.hasRunnableShape()) await query.run();
    } catch (e) {
      toasts.danger(i18n.t("toast.openFailed"), e instanceof Error ? e.message : String(e));
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
    toasts.info(i18n.t("toast.drillAcross"), i18n.t("toast.drillAcross.body").replace("{cube}", target.caption || target.name));
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
    toasts.success(i18n.t("toast.titlesSaved"), t.title || i18n.t("toast.titlesSaved.cleared"));
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
      <button
        class="tb-btn tb-btn--primary split-btn__main"
        class:tb-btn--disabled-shape={!runnable.ok}
        title={runnable.ok ? i18n.t("toolbar.run") : i18n.t(runnable.hintKey)}
        aria-disabled={!runnable.ok}
        disabled={!runnable.ok}
        onclick={onRun}
      >
        <Play size={18} /><span class="tb-btn__label">{i18n.t("toolbar.run")}</span>
      </button>
      <button
        class="tb-btn tb-btn--primary split-btn__caret"
        title={i18n.t("toolbar.runOptions")}
        aria-label={i18n.t("toolbar.runOptions")}
        onclick={(e) => { e.stopPropagation(); runMenuOpen = !runMenuOpen; toolsMenuOpen = false; exportMenuOpen = false; }}
      ><ChevronDown size={14} /></button>
    </div>
    {#if runMenuOpen}
      <div class="toolbar__dropdown">
        <label class="toolbar__check" title={i18n.t("toolbar.autorun.hint")}>
          <input type="checkbox" bind:checked={query.autorun} />
          <span>{i18n.t("toolbar.autorun")}</span>
        </label>
        {#if query.current?.type !== "MDX"}
          <label class="toolbar__check" title={i18n.t("toolbar.nonEmpty.hint")}>
            <input type="checkbox" bind:checked={nonEmpty} />
            <span>{i18n.t("toolbar.nonEmpty")}</span>
          </label>
          <label class="toolbar__check" title={i18n.t("toolbar.visualTotals.hint")}>
            <input type="checkbox" bind:checked={visualTotals} />
            <span>{i18n.t("toolbar.visualTotals")}</span>
          </label>
        {/if}
        <label class="toolbar__check" title={i18n.t("toolbar.async.hint")}>
          <input type="checkbox" bind:checked={query.async} />
          <span>{i18n.t("toolbar.async")}</span>
        </label>
      </div>
    {/if}
    {#if query.current?.type !== "MDX"}
      <button class="tb-btn" title={i18n.t("toolbar.swap")} aria-label={i18n.t("toolbar.swap")} onclick={() => query.swapAxes()}>
        <ArrowLeftRight size={18} />
      </button>
    {/if}
  </div>
  {#if onAskAi}
    <div class="toolbar__sep"></div>
    <div class="toolbar__group" role="group" aria-label={i18n.t("workspace.aiQuery.title")}>
      <button
        class="tb-btn tb-btn--ai"
        title={i18n.t("workspace.aiQuery.open")}
        aria-label={i18n.t("workspace.aiQuery.open")}
        onclick={() => onAskAi?.()}
      >
        <Sparkles size={18} />
        <span class="tb-btn__label">{i18n.t("workspace.aiQuery.title")}</span>
      </button>
    </div>
  {/if}
  <div class="toolbar__sep"></div>
  <div class="toolbar__group toolbar__menu" role="group" aria-label="Tools">
    <button
      class="tb-btn"
      onclick={(e) => { e.stopPropagation(); toolsMenuOpen = !toolsMenuOpen; exportMenuOpen = false; }}
      title={i18n.t("toolbar.tools")}
    >
      <Wrench size={18} /><span class="tb-btn__label">{i18n.t("toolbar.tools")}</span><ChevronDown size={14} />
    </button>
    {#if toolsMenuOpen}
      <div class="toolbar__dropdown">
        <button type="button" class="toolbar__item" onclick={onShowMdx}>
          <Braces size={16} /> <span>{i18n.t("toolbar.mdx")}…</span>
        </button>
        <button type="button" class="toolbar__item" onclick={openDrillthrough}>
          <Search size={16} /> <span>{i18n.t("toolbar.drillthrough")}…</span>
        </button>
        <button type="button" class="toolbar__item" onclick={openDrillAcross}>
          <ArrowLeftRight size={16} /> <span>{i18n.t("toolbar.drillAcross")}…</span>
        </button>
        <button type="button" class="toolbar__item" onclick={openReportTitles}>
          <Tags size={16} /> <span>{i18n.t("toolbar.reportTitles")}…</span>
        </button>
        <button
          type="button"
          class="toolbar__item"
          onclick={() => { toolsMenuOpen = false; platform.toggleFullscreen(); }}
          aria-pressed={platform.fullscreen}
        >
          {#if platform.fullscreen}
            <Minimize2 size={16} /> <span>{i18n.t("toolbar.fullscreen.exit")}</span>
          {:else}
            <Maximize2 size={16} /> <span>{i18n.t("toolbar.fullscreen.enter")}</span>
          {/if}
        </button>
      </div>
    {/if}
  </div>
  <div class="toolbar__spacer"></div>
  <div class="toolbar__group toolbar__menu" role="group" aria-label="Export">
    <button
      class="tb-btn"
      onclick={(e) => { e.stopPropagation(); exportMenuOpen = !exportMenuOpen; toolsMenuOpen = false; }}
      title={i18n.t("toolbar.export")}
    >
      <Download size={18} /><span class="tb-btn__label">{i18n.t("toolbar.export")}</span><ChevronDown size={14} />
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
    defaultName={saveAsMode ? `${i18n.t("saved.copyPrefix")} ${d.name}` : d.name}
    defaultFolder={d.folder}
    folders={repository.folders}
    open={saveOpen}
    onSave={onSavePick}
    onCancel={() => (saveOpen = false)}
  />
{/if}

<MDXModal
  mdx={mdxBuffer}
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
  title={i18n.t("modal.discard.title")}
  message={i18n.t("modal.discard.message")}
  confirmLabel={i18n.t("modal.discard.confirm")}
  cancelLabel={i18n.t("modal.discard.cancel")}
  variant="danger"
  open={confirmNewOpen}
  onConfirm={() => { confirmNewOpen = false; resetQuery(); }}
  onCancel={() => (confirmNewOpen = false)}
/>

<WarningModal
  title={i18n.t("modal.warning.title")}
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
  .toolbar__item:hover { background: var(--bg-hover); }
  .toolbar__sep {
    width: 1px;
    height: 22px;
    background: var(--border);
    margin: 0 4px;
  }
  .toolbar__spacer { flex: 1; }

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
  .tb-btn { transition: background var(--duration-fast), color var(--duration-fast); }
  .tb-btn:hover { background: var(--bg-hover); color: var(--fg); }
  .tb-btn:active { transform: translateY(1px); }
  .tb-btn__label { font-size: var(--fs-sm); }
  .tb-btn--primary {
    background: var(--accent);
    color: var(--bg);
    border-color: var(--accent);
  }
  .tb-btn--primary:hover { filter: brightness(1.1); background: var(--accent); color: var(--bg); }
  .tb-btn--ai {
    background: linear-gradient(135deg, var(--accent) 0%, color-mix(in srgb, var(--accent) 60%, #8b5cf6) 100%);
    color: var(--bg);
    border-color: var(--accent);
  }
  .tb-btn--ai:hover {
    filter: brightness(1.08);
    color: var(--bg);
  }
  /*
   * saiku-cloud#612 — visual cue that the query shape isn't runnable yet
   * (e.g. no measure selected). Distinct from the standard :disabled
   * appearance because we still want the user to see WHERE the Run
   * button is + read the tooltip explaining what's missing.
   */
  .tb-btn--disabled-shape,
  .tb-btn--disabled-shape:hover {
    background: var(--bg-muted);
    color: var(--fg-muted);
    border-color: var(--border);
    filter: none;
    cursor: not-allowed;
  }
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

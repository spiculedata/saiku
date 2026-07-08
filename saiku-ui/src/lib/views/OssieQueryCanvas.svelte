<script lang="ts">
  import { ossieQuery } from "$lib/stores/ossieQuery.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import { toasts } from "$lib/stores/toasts.svelte";
  import {
    ArrowLeftRight,
    ArrowDown,
    ArrowUp,
    BarChart3,
    Braces,
    ChevronDown,
    Copy,
    Download,
    FilePlus2,
    FileSpreadsheet,
    FileText,
    FileType,
    FolderOpen,
    Maximize2,
    Minimize2,
    Play,
    Redo2,
    Save,
    Table as TableIcon,
    Undo2,
    Wrench,
    X,
  } from "lucide-svelte";
  import ChartView from "$lib/views/ChartView.svelte";
  import { CHART_TYPES, type ChartType } from "$lib/views/chartTypes";
  import Modal from "$lib/components/Modal.svelte";
  import { previewOssieSql } from "$lib/api/ossie";
  import { downloadCsv, ossieResultToCsv } from "$lib/ossie/exportCsv";
  import { platform } from "$lib/stores/platform.svelte";
  import type { OssieFieldRef, OssieFilterExpr, OssieMetricRef } from "$lib/api/ossie";
  import SaveQueryModal from "$lib/modals/SaveQueryModal.svelte";
  import OssieLoadModal from "$lib/modals/OssieLoadModal.svelte";
  import { foldersOnly, listRepository } from "$lib/api/repository";
  import { pivotResult } from "$lib/ossie/pivot";

  const FIELD_MIME = "application/x-saiku-ossie-field";
  const METRIC_MIME = "application/x-saiku-ossie-metric";

  /**
   * Which shelf a drop should land on. Filters accept fields; Values accept metrics;
   * Rows / Columns accept fields. Passed to the drop handler so we don't need four near-
   * identical event handlers.
   */
  type Shelf = "rows" | "columns" | "values" | "filters";

  function acceptsField(shelf: Shelf): boolean {
    return shelf === "rows" || shelf === "columns" || shelf === "filters";
  }

  function acceptsMetric(shelf: Shelf): boolean {
    return shelf === "values";
  }

  function onDragOver(e: DragEvent, shelf: Shelf) {
    if (!e.dataTransfer) return;
    const types = e.dataTransfer.types;
    const fieldOk = acceptsField(shelf) && types.includes(FIELD_MIME);
    const metricOk = acceptsMetric(shelf) && types.includes(METRIC_MIME);
    if (fieldOk || metricOk) {
      e.preventDefault();
      e.dataTransfer.dropEffect = "copy";
    }
  }

  function onDrop(e: DragEvent, shelf: Shelf) {
    if (!e.dataTransfer) return;
    e.preventDefault();
    if (acceptsField(shelf)) {
      const raw = e.dataTransfer.getData(FIELD_MIME);
      if (raw) {
        const ref = JSON.parse(raw) as OssieFieldRef;
        if (shelf === "rows") ossieQuery.addRow(ref);
        else if (shelf === "columns") ossieQuery.addColumn(ref);
        else if (shelf === "filters") {
          // Filters need an operator + value; drop-and-fill mini-form comes next iteration.
          // MVP: drop creates an EQ-with-empty-value filter the user then edits inline.
          const expr: OssieFilterExpr = {
            dataset: ref.dataset,
            field: ref.field,
            op: "EQ",
            value: "",
            values: [],
          };
          ossieQuery.addFilter(expr);
        }
        return;
      }
    }
    if (acceptsMetric(shelf)) {
      const raw = e.dataTransfer.getData(METRIC_MIME);
      if (raw) {
        const ref = JSON.parse(raw) as OssieMetricRef;
        ossieQuery.addValue(ref);
      }
    }
  }

  async function runQuery() {
    await ossieQuery.run();
  }

  // ------------------------------------------------------------------
  // Save modal state
  // ------------------------------------------------------------------
  let saveModalOpen = $state(false);
  let saveFolders = $state<string[]>([]);
  let saveDefaultFolder = $state("");
  let saveDefaultName = $state("");

  /**
   * Prepare the SaveQueryModal state and open it. Loads the folder tree up-front so the
   * modal's browser has something to render; defaults the target folder to the previously-
   * saved location (or the user's home) and the name to the previously-saved value.
   */
  async function openSaveModal() {
    if (!ossieQuery.current) return;
    try {
      const tree = await listRepository(["saiku"]);
      saveFolders = foldersOnly(tree);
    } catch (e) {
      // If the folder list can't load, still let the user save into their home — the
      // browser degrades to typed-only input for the folder.
      saveFolders = [];
      toasts.warning?.("Repository listing failed", e instanceof Error ? e.message : String(e));
    }
    const priorPath = ossieQuery.savedPath;
    if (priorPath) {
      const idx = priorPath.lastIndexOf("/");
      saveDefaultFolder = idx > 0 ? priorPath.substring(0, idx) : "";
    } else {
      saveDefaultFolder = `/homes/home:${session.current?.username ?? "admin"}`;
    }
    saveDefaultName = ossieQuery.savedName ?? "untitled-ossie-query";
    saveModalOpen = true;
  }

  /**
   * Persist the current shelf state to the folder + name the modal returned. Path
   * assembly uses the same convention as the MDX side: `<folder>/<name>.saiku`.
   */
  async function onModalSave(folder: string, name: string) {
    if (!ossieQuery.current) return;
    const cleanFolder = folder ? (folder.startsWith("/") ? folder : `/${folder}`) : "";
    const path = `${cleanFolder}/${name}.saiku`.replace(/\/{2,}/g, "/");
    saveModalOpen = false;
    try {
      await ossieQuery.save(path, name);
      toasts.success("Saved", path);
    } catch (e) {
      toasts.danger("Save failed", e instanceof Error ? e.message : String(e));
    }
  }

  // ------------------------------------------------------------------
  // Load modal state
  // ------------------------------------------------------------------
  let loadModalOpen = $state(false);

  function openLoadModal() {
    loadModalOpen = true;
  }

  /**
   * Called by the modal when the user picks + confirms a file. Hands off to the store's
   * load and re-selects the connection so the sidebar refreshes. Falls back with a
   * friendly toast when the file is MDX-shaped — the user picked from a mixed folder,
   * they need to open it from the MDX workbench.
   */
  async function onModalLoad(path: string) {
    loadModalOpen = false;
    try {
      const loaded = await ossieQuery.load(path);
      if (!loaded) {
        toasts.danger(
          "Not an Ossie query",
          "This file is a Mondrian/MDX query. Open it from the MDX workbench.",
        );
        return;
      }
      const conn = loaded.ossieQueryModel.connection;
      const model = loaded.ossieQueryModel.model;
      // Flip the selection so the sidebar reloads the semantic model tree matching what
      // the saved shelf state references. loadModel is memoised on connection name so a
      // reload of the current connection is a no-op.
      selection.selectOssie({ connectionName: conn, modelName: model });
      const user = session.current?.username;
      if (user) await ossieQuery.loadModel(user, conn, model);
      toasts.success("Loaded", loaded.name);
    } catch (e) {
      toasts.danger("Load failed", e instanceof Error ? e.message : String(e));
    }
  }

  function updateFilterOp(idx: number, op: OssieFilterExpr["op"]) {
    if (!ossieQuery.current) return;
    const filters = ossieQuery.current.filters.map((f, i) => {
      if (i !== idx) return f;
      // When switching to a multi-value op (BETWEEN / IN), seed values from the current
      // single-value input so the user doesn't lose what they typed. Conversely, when
      // switching back to a single-value op, pull the first entry from values[].
      const isMulti = op === "BETWEEN" || op === "IN";
      const values = isMulti ? (f.values.length ? f.values : f.value ? [f.value] : []) : f.values;
      const value = !isMulti && f.value === undefined && values.length ? values[0] : f.value;
      return { ...f, op, values, value };
    });
    ossieQuery.current = { ...ossieQuery.current, filters };
  }

  function updateFilterValue(idx: number, value: string) {
    if (!ossieQuery.current) return;
    const filters = ossieQuery.current.filters.map((f, i) => (i === idx ? { ...f, value } : f));
    ossieQuery.current = { ...ossieQuery.current, filters };
  }

  /**
   * Update one entry in a filter's multi-value list (BETWEEN slot 0/1, IN nth entry).
   * Immutable copy so Svelte 5 dependency-tracking fires. Grows the array with empty
   * strings if the index is past the end so subsequent inputs land in the right slot.
   */
  function updateFilterValueAt(idx: number, valueIdx: number, value: string) {
    if (!ossieQuery.current) return;
    const filters = ossieQuery.current.filters.map((f, i) => {
      if (i !== idx) return f;
      const values = [...f.values];
      while (values.length <= valueIdx) values.push("");
      values[valueIdx] = value;
      return { ...f, values };
    });
    ossieQuery.current = { ...ossieQuery.current, filters };
  }

  function addFilterValue(idx: number) {
    if (!ossieQuery.current) return;
    const filters = ossieQuery.current.filters.map((f, i) => {
      if (i !== idx) return f;
      return { ...f, values: [...f.values, ""] };
    });
    ossieQuery.current = { ...ossieQuery.current, filters };
  }

  function removeFilterValue(idx: number, valueIdx: number) {
    if (!ossieQuery.current) return;
    const filters = ossieQuery.current.filters.map((f, i) => {
      if (i !== idx) return f;
      return { ...f, values: f.values.filter((_, j) => j !== valueIdx) };
    });
    ossieQuery.current = { ...ossieQuery.current, filters };
  }

  const runnable = $derived(ossieQuery.hasRunnableShape());

  /**
   * Header-click sort dispatcher. Maps an on-screen header column back to its shelf entry
   * so the store can cycle its sort direction.
   *
   * - Row-shelf header → sort by { dataset, field } (matches OssieShelfSqlTranslator's
   *   field expression on the same fully-qualified column).
   * - Column-shelf value header (top row in a crosstab) → currently ignored; sorting a
   *   pivoted column-value would require a per-metric sort which we skip in P1.
   * - Metric header → sort by metric name.
   */
  function onSortHeaderClick(target: import("$lib/api/ossie").OssieSortRef, e: MouseEvent) {
    ossieQuery.cycleSort(target, e.shiftKey);
    // Fire a re-run so the user sees the sort take effect immediately.
    void ossieQuery.run();
  }

  /**
   * Look up an active sort entry by matching (dataset, field) or metric — used to render
   * the up/down arrow indicator on column headers.
   */
  function activeSortDirection(
    target: import("$lib/api/ossie").OssieSortRef,
  ): "ASC" | "DESC" | null {
    const sort = ossieQuery.current?.sorts.find(
      (s) => s.metric === target.metric && s.dataset === target.dataset && s.field === target.field,
    );
    return sort?.direction ?? null;
  }

  // ------------------------------------------------------------------
  // Toolbar affordances (P1): LIMIT + Swap Axes
  // ------------------------------------------------------------------

  /** LIMIT input value. Bound directly to a number input; `null` when the field is blank. */
  let limitInput = $state<number | null>(null);

  // Two-way sync between the store's shelf state and the input: reflect any external change
  // (Load, Undo/Redo) into the input, and vice-versa. Using an $effect for the store→input
  // direction; a direct on:change handler for the input→store direction.
  $effect(() => {
    limitInput = ossieQuery.current?.limit ?? null;
  });

  function commitLimit() {
    ossieQuery.setLimit(limitInput);
  }

  function onSwapAxes() {
    ossieQuery.swapAxes();
    if (ossieQuery.result) void ossieQuery.run();
  }

  // ------------------------------------------------------------------
  // Export CSV
  // ------------------------------------------------------------------
  function onExportCsv() {
    if (!ossieQuery.result) return;
    const base = ossieQuery.savedName ?? "ossie-query";
    downloadCsv(`${base}.csv`, ossieResultToCsv(ossieQuery.result));
  }

  // ------------------------------------------------------------------
  // New — clear the shelf state back to an empty query for the current
  // model. Called from the leftmost toolbar button (matches MDX's FilePlus2).
  // ------------------------------------------------------------------
  function onNew() {
    if (!ossieQuery.current) return;
    // No confirm-if-dirty prompt yet; MDX has one gated on query.dirty. Follow-up if
    // users start losing work.
    ossieQuery.captureForUndo();
    ossieQuery.current = {
      ...ossieQuery.current,
      rows: [],
      columns: [],
      values: [],
      filters: [],
      sorts: [],
    };
    ossieQuery.result = null;
    ossieQuery.rawResult = null;
  }

  // ------------------------------------------------------------------
  // Dropdown menus (Tools / Export) — mirror MDX toolbar behaviour.
  // Only one dropdown open at a time; clicking outside closes them.
  // ------------------------------------------------------------------
  let toolsMenuOpen = $state(false);
  let exportMenuOpen = $state(false);

  function handleBodyClick() {
    toolsMenuOpen = false;
    exportMenuOpen = false;
  }

  // ------------------------------------------------------------------
  // Show SQL — server-side preview so what we display matches what the
  // executor actually runs (rather than duplicating translation logic).
  // ------------------------------------------------------------------
  let showSqlOpen = $state(false);
  let showSqlText = $state<string>("");
  let showSqlLoading = $state(false);
  let showSqlError = $state<string | null>(null);

  async function onShowSql() {
    if (!ossieQuery.current) return;
    showSqlText = "";
    showSqlError = null;
    showSqlOpen = true;
    showSqlLoading = true;
    try {
      showSqlText = await previewOssieSql(ossieQuery.current);
    } catch (e) {
      showSqlError = e instanceof Error ? e.message : String(e);
    } finally {
      showSqlLoading = false;
    }
  }

  // ------------------------------------------------------------------
  // Undo / redo + Cmd-Z shortcut
  // ------------------------------------------------------------------

  /**
   * Global Cmd-Z / Shift-Cmd-Z listener. Registered while the Ossie canvas is mounted;
   * skips when the target is an input / textarea / contenteditable so the shortcut
   * doesn't fight text editing. Matches the MDX Workspace-level binding.
   */
  $effect(() => {
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target) {
        const tag = target.tagName;
        if (tag === "INPUT" || tag === "TEXTAREA" || target.isContentEditable) return;
      }
      const meta = e.metaKey || e.ctrlKey;
      if (!meta || e.key.toLowerCase() !== "z") return;
      e.preventDefault();
      if (e.shiftKey) ossieQuery.redo();
      else ossieQuery.undo();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  });

  /**
   * Map a header index in the long-form result grid back to its sort target. The
   * column order matches OssieShelfSqlTranslator's SELECT list: `rows` first, then
   * `columns`, then `values`. Long-form only renders when `columns` is empty, so the
   * split is just `rows.length` metric columns follow the row-shelf ones.
   */
  function longFormSortTarget(columnIndex: number): import("$lib/api/ossie").OssieSortRef | null {
    const q = ossieQuery.current;
    if (!q) return null;
    const rowCount = q.rows.length;
    if (columnIndex < rowCount) {
      const ref = q.rows[columnIndex];
      return { dataset: ref.dataset, field: ref.field, direction: "ASC" };
    }
    const valueIndex = columnIndex - rowCount;
    const metric = q.values[valueIndex];
    if (!metric) return null;
    return { metric: metric.metric, direction: "ASC" };
  }

  /**
   * Pivoted view of the current result — non-null only when the user has entries on the
   * Columns shelf. The renderer picks between the crosstab grid and the flat table by
   * looking at this value.
   */
  const pivot = $derived.by(() => {
    const q = ossieQuery.current;
    const r = ossieQuery.result;
    if (!q || !r) return null;
    if (q.columns.length === 0) return null;
    const rowLabels = q.rows.map((f) => `${f.dataset}.${f.field}`);
    const colLabels = q.columns.map((f) => `${f.dataset}.${f.field}`);
    const valLabels = q.values.map((v) => v.metric);
    return pivotResult(rowLabels, colLabels, valLabels, r);
  });
</script>

<svelte:window onclick={handleBodyClick} />

<div class="ossie-canvas">
  <!-- Toolbar mirrors WorkspaceToolbar structurally: file group / undo-redo / run+swap /
       tools / grow / view-toggle+chart-type / export. Ossie-only bits use the same
       tb-btn styling so the two toolbars read as siblings, not as different products. -->
  <div class="toolbar" role="toolbar" aria-label="Ossie workbench toolbar">
    <div class="flex items-center gap-0.5 relative" role="group" aria-label="File">
      <button
        class="tb-btn"
        title="New"
        aria-label="New"
        onclick={onNew}
        disabled={!ossieQuery.current}
      >
        <FilePlus2 size={18} />
      </button>
      <button class="tb-btn" title="Open" aria-label="Open" onclick={openLoadModal}>
        <FolderOpen size={18} />
      </button>
      <button
        class="tb-btn"
        title="Save"
        aria-label="Save"
        onclick={openSaveModal}
        disabled={!ossieQuery.current}
      >
        <Save size={18} />
      </button>
      <button
        class="tb-btn"
        title="Save as"
        aria-label="Save as"
        onclick={openSaveModal}
        disabled={!ossieQuery.current}
      >
        <Copy size={18} />
      </button>
    </div>
    <div class="toolbar__sep"></div>
    <div class="flex items-center gap-0.5 relative" role="group" aria-label="Undo / redo">
      <button
        class="tb-btn"
        title={platform.isMac ? "Undo (⌘Z)" : "Undo (Ctrl+Z)"}
        aria-label="Undo"
        disabled={!ossieQuery.canUndo}
        onclick={() => ossieQuery.undo()}
      >
        <Undo2 size={18} />
      </button>
      <button
        class="tb-btn"
        title={platform.isMac ? "Redo (⇧⌘Z)" : "Redo (Ctrl+Shift+Z)"}
        aria-label="Redo"
        disabled={!ossieQuery.canRedo}
        onclick={() => ossieQuery.redo()}
      >
        <Redo2 size={18} />
      </button>
    </div>
    <div class="toolbar__sep"></div>
    <div class="flex items-center gap-0.5 relative" role="group" aria-label="Query">
      <button
        class="tb-btn tb-btn--primary"
        class:tb-btn--disabled-shape={!runnable}
        title={runnable ? "Run" : "Drop fields on Rows/Columns and a metric on Values first"}
        aria-disabled={!runnable}
        disabled={!runnable || ossieQuery.running}
        onclick={runQuery}
      >
        <Play size={18} /><span class="text-sm">{ossieQuery.running ? "Running…" : "Run"}</span>
      </button>
      <button
        class="tb-btn"
        title="Swap Rows and Columns"
        aria-label="Swap axes"
        onclick={onSwapAxes}
        disabled={!ossieQuery.current
          || (ossieQuery.current.rows.length === 0 && ossieQuery.current.columns.length === 0)}
      >
        <ArrowLeftRight size={18} />
      </button>
    </div>
    <div class="toolbar__sep"></div>
    <div class="flex items-center gap-0.5 relative" role="group" aria-label="Tools">
      <button
        class="tb-btn"
        onclick={(e) => {
          e.stopPropagation();
          toolsMenuOpen = !toolsMenuOpen;
          exportMenuOpen = false;
        }}
        title="Tools"
      >
        <Wrench size={18} /><span class="text-sm">Tools</span><ChevronDown size={14} />
      </button>
      {#if toolsMenuOpen}
        <div class="toolbar__dropdown">
          <button
            type="button"
            class="toolbar__item"
            onclick={() => {
              toolsMenuOpen = false;
              void onShowSql();
            }}
            disabled={!ossieQuery.current}
          >
            <Braces size={16} /> <span>Show SQL…</span>
          </button>
          <button
            type="button"
            class="toolbar__item"
            onclick={() => {
              toolsMenuOpen = false;
              platform.toggleFullscreen();
            }}
            aria-pressed={platform.fullscreen}
          >
            {#if platform.fullscreen}
              <Minimize2 size={16} /> <span>Exit fullscreen</span>
            {:else}
              <Maximize2 size={16} /> <span>Enter fullscreen</span>
            {/if}
          </button>
        </div>
      {/if}
    </div>
    <div class="toolbar__sep"></div>
    <label class="ossie-canvas__limit" title="Cap the emitted SQL with LIMIT N (blank = no limit)">
      <span class="ossie-canvas__limit-label">LIMIT</span>
      <input
        type="number"
        min="1"
        step="1"
        class="ossie-canvas__limit-input"
        placeholder="—"
        bind:value={limitInput}
        onchange={commitLimit}
      />
    </label>
    {#if ossieQuery.savedName}
      <span class="ossie-canvas__saved-name">{ossieQuery.savedName}</span>
    {/if}
    {#if ossieQuery.error}
      <span class="ossie-canvas__error">{ossieQuery.error}</span>
    {/if}
    {#if ossieQuery.result?.runtime !== undefined}
      <span class="ossie-canvas__runtime">{ossieQuery.result.runtime}ms</span>
    {/if}
    <div class="flex-1"></div>
    <div class="flex items-center gap-0.5" role="tablist" aria-label="View mode">
      <button
        type="button"
        role="tab"
        aria-selected={ossieQuery.viewMode === "grid"}
        class="tb-btn"
        class:tb-btn--primary={ossieQuery.viewMode === "grid"}
        onclick={() => (ossieQuery.viewMode = "grid")}
      >
        <TableIcon size={16} /><span class="text-sm">Grid</span>
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={ossieQuery.viewMode === "chart"}
        class="tb-btn"
        class:tb-btn--primary={ossieQuery.viewMode === "chart"}
        onclick={() => (ossieQuery.viewMode = "chart")}
      >
        <BarChart3 size={16} /><span class="text-sm">Chart</span>
      </button>
    </div>
    {#if ossieQuery.viewMode === "chart"}
      <select
        class="ossie-canvas__charttype"
        bind:value={ossieQuery.chartType}
        aria-label="Chart type"
      >
        {#each CHART_TYPES as t (t.id)}
          <option value={t.id}>{t.label}</option>
        {/each}
      </select>
    {/if}
    <div class="flex items-center gap-0.5 relative" role="group" aria-label="Export">
      <button
        class="tb-btn"
        onclick={(e) => {
          e.stopPropagation();
          exportMenuOpen = !exportMenuOpen;
          toolsMenuOpen = false;
        }}
        title="Export"
        disabled={!ossieQuery.result}
      >
        <Download size={18} /><span class="text-sm">Export</span><ChevronDown size={14} />
      </button>
      {#if exportMenuOpen}
        <div class="toolbar__dropdown left-auto right-0">
          <button
            type="button"
            class="toolbar__item"
            onclick={() => {
              exportMenuOpen = false;
              onExportCsv();
            }}
          >
            <FileText size={16} /> <span>CSV</span>
          </button>
          <button
            type="button"
            class="toolbar__item"
            disabled
            title="XLS export coming next"
          >
            <FileSpreadsheet size={16} /> <span>XLS (coming soon)</span>
          </button>
          <button
            type="button"
            class="toolbar__item"
            disabled
            title="PDF export coming next"
          >
            <FileType size={16} /> <span>PDF (coming soon)</span>
          </button>
        </div>
      {/if}
    </div>
  </div>

  <div class="ossie-canvas__shelves">
    <div
      class="ossie-shelf"
      ondragover={(e) => onDragOver(e, "rows")}
      ondrop={(e) => onDrop(e, "rows")}
      role="group"
      aria-label="Rows shelf"
    >
      <span class="ossie-shelf__label">Rows</span>
      {#each ossieQuery.current?.rows ?? [] as f, i}
        <span class="ossie-chip">
          {f.dataset}.{f.field}
          <button
            type="button"
            class="ossie-chip__x"
            aria-label="Remove {f.field}"
            onclick={() => ossieQuery.removeRow(i)}
          ><X size={12} /></button>
        </span>
      {/each}
    </div>

    <div
      class="ossie-shelf"
      ondragover={(e) => onDragOver(e, "columns")}
      ondrop={(e) => onDrop(e, "columns")}
      role="group"
      aria-label="Columns shelf"
    >
      <span class="ossie-shelf__label">Columns</span>
      {#each ossieQuery.current?.columns ?? [] as f, i}
        <span class="ossie-chip">
          {f.dataset}.{f.field}
          <button
            type="button"
            class="ossie-chip__x"
            aria-label="Remove {f.field}"
            onclick={() => ossieQuery.removeColumn(i)}
          ><X size={12} /></button>
        </span>
      {/each}
    </div>

    <div
      class="ossie-shelf"
      ondragover={(e) => onDragOver(e, "values")}
      ondrop={(e) => onDrop(e, "values")}
      role="group"
      aria-label="Values shelf"
    >
      <span class="ossie-shelf__label">Values</span>
      {#each ossieQuery.current?.values ?? [] as v, i}
        <span class="ossie-chip ossie-chip--metric">
          Σ {v.metric}
          <button
            type="button"
            class="ossie-chip__x"
            aria-label="Remove {v.metric}"
            onclick={() => ossieQuery.removeValue(i)}
          ><X size={12} /></button>
        </span>
      {/each}
    </div>

    <div
      class="ossie-shelf"
      ondragover={(e) => onDragOver(e, "filters")}
      ondrop={(e) => onDrop(e, "filters")}
      role="group"
      aria-label="Filters shelf"
    >
      <span class="ossie-shelf__label">Filters</span>
      {#each ossieQuery.current?.filters ?? [] as f, i}
        <span class="ossie-chip ossie-chip--filter">
          <span class="ossie-chip__col">{f.dataset ?? ""}.{f.field}</span>
          <select
            class="ossie-chip__op"
            value={f.op}
            onchange={(e) => updateFilterOp(i, (e.currentTarget as HTMLSelectElement).value as OssieFilterExpr["op"])}
          >
            <option value="EQ">=</option>
            <option value="NEQ">≠</option>
            <option value="LT">&lt;</option>
            <option value="LTE">≤</option>
            <option value="GT">&gt;</option>
            <option value="GTE">≥</option>
            <option value="BETWEEN">between</option>
            <option value="IN">in</option>
            <option value="IS_NULL">is null</option>
            <option value="IS_NOT_NULL">is not null</option>
          </select>
          {#if f.op === "BETWEEN"}
            <input
              class="ossie-chip__value"
              value={f.values?.[0] ?? ""}
              oninput={(e) => updateFilterValueAt(i, 0, (e.currentTarget as HTMLInputElement).value)}
              placeholder="from"
            />
            <span class="ossie-chip__and">and</span>
            <input
              class="ossie-chip__value"
              value={f.values?.[1] ?? ""}
              oninput={(e) => updateFilterValueAt(i, 1, (e.currentTarget as HTMLInputElement).value)}
              placeholder="to"
            />
          {:else if f.op === "IN"}
            {#each f.values ?? [] as v, vi (vi)}
              <span class="ossie-chip__in-slot">
                <input
                  class="ossie-chip__value"
                  value={v}
                  oninput={(e) => updateFilterValueAt(i, vi, (e.currentTarget as HTMLInputElement).value)}
                  placeholder="value"
                />
                <button
                  type="button"
                  class="ossie-chip__x"
                  aria-label="Remove value"
                  onclick={() => removeFilterValue(i, vi)}
                ><X size={10} /></button>
              </span>
            {/each}
            <button
              type="button"
              class="ossie-chip__add-value"
              aria-label="Add value"
              onclick={() => addFilterValue(i)}
            >+</button>
          {:else if f.op !== "IS_NULL" && f.op !== "IS_NOT_NULL"}
            <input
              class="ossie-chip__value"
              value={f.value ?? ""}
              oninput={(e) => updateFilterValue(i, (e.currentTarget as HTMLInputElement).value)}
              placeholder="value"
            />
          {/if}
          <button
            type="button"
            class="ossie-chip__x"
            aria-label="Remove filter"
            onclick={() => ossieQuery.removeFilter(i)}
          ><X size={12} /></button>
        </span>
      {/each}
    </div>
  </div>

  <div class="ossie-canvas__result">
    {#if ossieQuery.viewMode === "chart" && ossieQuery.rawResult && ossieQuery.result && (ossieQuery.result.cellSetBody?.length ?? 0) > 0}
      <!-- Chart view uses the shared ECharts wrapper. It expects the raw QueryResult
           envelope; the store keeps it alongside the pivot-friendly projection. Chart
           options + sort/limit/palette all come from the same ChartOptions type the
           MDX side uses, so the whole existing chart-editor modal will Just Work
           once P3 wires the gear button. -->
      <ChartView
        result={ossieQuery.rawResult}
        type={ossieQuery.chartType}
        options={ossieQuery.chartOptions}
      />
    {:else if ossieQuery.viewMode === "chart" && ossieQuery.result && (ossieQuery.result.cellSetBody?.length ?? 0) === 0}
      <p class="ossie-canvas__empty">Query returned no rows.</p>
    {:else if ossieQuery.result && pivot}
      <!-- Crosstab render: Columns shelf has entries so the flat rowset pivots into a
           real row × col grid. Multi-level columns collapse via colspan; missing
           intersections render as empty cells. -->
      <table class="ossie-result">
        <thead>
          {#each pivot.headerRows as headerRow}
            <tr>
              {#each headerRow as h}
                <th colspan={h.colspan ?? 1}>{h.formatted}</th>
              {/each}
            </tr>
          {/each}
        </thead>
        <tbody>
          {#each pivot.bodyRows as row}
            <tr>
              {#each row as c, i}
                {#if c.isHeader && i < pivot.rowHeaderCount}
                  <th class="ossie-result__row-header">{c.formatted}</th>
                {:else}
                  <td class:ossie-result__num={c.isNumeric}>{c.formatted}</td>
                {/if}
              {/each}
            </tr>
          {/each}
        </tbody>
      </table>
      {#if pivot.bodyRows.length === 0}
        <p class="ossie-canvas__empty">Query returned no rows.</p>
      {/if}
    {:else if ossieQuery.result}
      <!-- Long-form fallback: no Columns shelf entries → one column per shelf field
           straight from the server, no pivot. Header cells are clickable to cycle the
           sort direction on that shelf entry / metric. -->
      <table class="ossie-result">
        <thead>
          <tr>
            {#each ossieQuery.result.cellSetHeaders?.[0] ?? [] as h, i}
              {@const headerTarget = longFormSortTarget(i)}
              <th>
                {#if headerTarget}
                  {@const dir = activeSortDirection(headerTarget)}
                  <button
                    type="button"
                    class="ossie-result__sort-btn"
                    class:ossie-result__sort-btn--active={dir !== null}
                    onclick={(e) => onSortHeaderClick(headerTarget, e)}
                    title="Click to sort (Shift-click to add a secondary sort)"
                  >
                    <span>{h.formattedValue ?? h.rawValue ?? ""}</span>
                    {#if dir === "ASC"}<ArrowUp size={12} />{/if}
                    {#if dir === "DESC"}<ArrowDown size={12} />{/if}
                  </button>
                {:else}
                  {h.formattedValue ?? h.rawValue ?? ""}
                {/if}
              </th>
            {/each}
          </tr>
        </thead>
        <tbody>
          {#each ossieQuery.result.cellSetBody ?? [] as row}
            <tr>
              {#each row as c}
                <td class:ossie-result__num={c.rawNumber !== undefined}>
                  {c.formattedValue ?? c.rawValue ?? ""}
                </td>
              {/each}
            </tr>
          {/each}
        </tbody>
      </table>
      {#if (ossieQuery.result.cellSetBody?.length ?? 0) === 0}
        <p class="ossie-canvas__empty">Query returned no rows.</p>
      {/if}
    {:else if runnable}
      <p class="ossie-canvas__hint">Ready to run. Hit Run to execute.</p>
    {:else}
      <p class="ossie-canvas__hint">
        Drag fields onto Rows/Columns, metrics onto Values, and pick a fact dataset.
      </p>
    {/if}
  </div>
</div>

<SaveQueryModal
  open={saveModalOpen}
  defaultName={saveDefaultName}
  defaultFolder={saveDefaultFolder}
  folders={saveFolders}
  onSave={onModalSave}
  onCancel={() => (saveModalOpen = false)}
/>

<OssieLoadModal
  open={loadModalOpen}
  initialPath={ossieQuery.savedPath ?? ""}
  onOpen={onModalLoad}
  onCancel={() => (loadModalOpen = false)}
/>

<Modal title="Generated SQL" open={showSqlOpen} size="lg" onClose={() => (showSqlOpen = false)}>
  {#if showSqlLoading}
    <p class="ossie-canvas__hint">Loading…</p>
  {:else if showSqlError}
    <p class="callout callout--danger">{showSqlError}</p>
  {:else}
    <pre class="ossie-canvas__sql">{showSqlText}</pre>
  {/if}
</Modal>

<style>
  .ossie-canvas {
    display: flex;
    flex-direction: column;
    height: 100%;
  }
  /* Toolbar styles ported from WorkspaceToolbar.svelte so the Ossie toolbar mirrors
     the MDX one visually. Any change here or there should be mirrored to the other. */
  .toolbar {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 10px;
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
    flex-wrap: wrap;
  }
  .toolbar__dropdown {
    position: absolute;
    top: calc(100% + 6px);
    left: 0;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35);
    padding: 4px;
    z-index: 50;
    min-width: 220px;
  }
  .toolbar__dropdown.left-auto {
    left: auto;
  }
  .toolbar__dropdown.right-0 {
    right: 0;
  }
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
  .toolbar__item:hover {
    background: var(--bg-hover);
  }
  .toolbar__item:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .toolbar__sep {
    width: 1px;
    height: 22px;
    background: var(--border);
    margin: 0 4px;
  }
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
    transition: background var(--duration-fast), color var(--duration-fast);
  }
  .tb-btn:hover {
    background: var(--bg-hover);
    color: var(--fg);
  }
  .tb-btn:active {
    transform: translateY(1px);
  }
  .tb-btn:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
  .tb-btn--primary {
    background: var(--accent);
    color: var(--bg);
    border-color: var(--accent);
  }
  .tb-btn--primary:hover {
    filter: brightness(1.1);
    background: var(--accent);
    color: var(--bg);
  }
  .tb-btn--disabled-shape,
  .tb-btn--disabled-shape:hover {
    background: var(--bg-muted);
    color: var(--fg-muted);
    border-color: var(--border);
    filter: none;
    cursor: not-allowed;
  }
  .ossie-canvas__error {
    color: var(--danger-fg, #b91c1c);
    font-size: var(--fs-sm);
  }
  .ossie-canvas__runtime {
    color: var(--fg-subtle);
    font-size: var(--fs-xs);
    margin-left: auto;
  }
  .ossie-canvas__saved-name {
    color: var(--fg-muted);
    font-size: var(--fs-xs);
    font-family: monospace;
  }
  .ossie-canvas__limit {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 0 8px;
    border: 1px solid var(--border);
    border-radius: 5px;
    background: transparent;
    height: 32px;
  }
  .ossie-canvas__limit-label {
    font-size: var(--fs-xs);
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.06em;
  }
  .ossie-canvas__limit-input {
    background: transparent;
    border: none;
    color: var(--fg);
    font-size: var(--fs-sm);
    width: 60px;
    padding: 0;
  }
  .ossie-canvas__limit-input:focus {
    outline: none;
  }
  .ossie-canvas__limit-input::-webkit-inner-spin-button,
  .ossie-canvas__limit-input::-webkit-outer-spin-button {
    -webkit-appearance: none;
    margin: 0;
  }
  .ossie-result__sort-btn {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    background: transparent;
    border: none;
    color: inherit;
    font: inherit;
    padding: 0;
    cursor: pointer;
    text-align: left;
  }
  .ossie-result__sort-btn:hover {
    color: var(--fg);
  }
  .ossie-result__sort-btn--active {
    color: var(--accent);
  }
  .ossie-canvas__charttype {
    padding: 6px 8px;
    background: transparent;
    border: 1px solid var(--border);
    border-radius: 5px;
    color: var(--fg);
    font-size: var(--fs-sm);
    height: 32px;
  }
  .ossie-canvas__sql {
    background: var(--bg-hover);
    padding: 12px;
    border-radius: 4px;
    font-family: monospace;
    font-size: var(--fs-sm);
    color: var(--fg);
    white-space: pre-wrap;
    max-height: 60vh;
    overflow: auto;
    margin: 0;
  }
  .ossie-chip__and {
    color: var(--fg-subtle);
    font-size: var(--fs-xs);
    padding: 0 2px;
  }
  .ossie-chip__in-slot {
    display: inline-flex;
    align-items: center;
    gap: 2px;
  }
  .ossie-chip__add-value {
    background: transparent;
    border: 1px dashed var(--border);
    color: var(--fg-subtle);
    border-radius: 3px;
    padding: 1px 6px;
    font-size: var(--fs-xs);
    cursor: pointer;
  }
  .ossie-chip__add-value:hover {
    color: var(--fg);
    border-color: var(--border-strong);
  }
  .ossie-canvas__shelves {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-3) 0;
  }
  .ossie-shelf {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
    min-height: 42px;
    padding: 8px 12px;
    background: var(--bg-subtle, var(--bg-hover));
    border: 1px dashed var(--border-strong);
    border-radius: 4px;
  }
  .ossie-shelf__label {
    font-size: var(--fs-xs);
    font-weight: var(--weight-semibold);
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
    margin-right: 8px;
    min-width: 60px;
  }
  .ossie-chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 8px;
    background: var(--bg);
    border: 1px solid var(--border-strong);
    border-radius: 4px;
    font-size: var(--fs-sm);
  }
  .ossie-chip--metric {
    background: var(--bg-accent-subtle, var(--bg-hover));
  }
  .ossie-chip--filter {
    gap: 6px;
  }
  .ossie-chip__col {
    font-family: monospace;
    font-size: var(--fs-xs);
  }
  .ossie-chip__op {
    background: transparent;
    border: 1px solid var(--border);
    border-radius: 3px;
    padding: 1px 4px;
    font-size: var(--fs-xs);
  }
  .ossie-chip__value {
    background: transparent;
    border: 1px solid var(--border);
    border-radius: 3px;
    padding: 2px 6px;
    font-size: var(--fs-xs);
    width: 100px;
  }
  .ossie-chip__x {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: transparent;
    border: none;
    color: var(--fg-subtle);
    cursor: pointer;
    padding: 0;
    margin-left: 2px;
  }
  .ossie-chip__x:hover {
    color: var(--fg);
  }
  .ossie-canvas__result {
    flex: 1;
    min-height: 0;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: var(--space-2);
    margin: var(--space-3);
    margin-top: var(--space-3);
  }
  .ossie-result {
    width: 100%;
    border-collapse: collapse;
    font-size: var(--fs-sm);
  }
  .ossie-result th,
  .ossie-result td {
    padding: 6px 10px;
    border-bottom: 1px solid var(--border);
    text-align: left;
  }
  .ossie-result th {
    background: var(--bg-hover);
    font-weight: var(--weight-semibold);
    position: sticky;
    top: 0;
  }
  .ossie-result__num {
    text-align: right;
    font-variant-numeric: tabular-nums;
  }
  .ossie-result__row-header {
    background: transparent;
    font-weight: var(--weight-medium);
    text-align: left;
    position: static;
  }
  .ossie-canvas__hint,
  .ossie-canvas__empty {
    color: var(--fg-subtle);
    font-size: var(--fs-sm);
  }
</style>

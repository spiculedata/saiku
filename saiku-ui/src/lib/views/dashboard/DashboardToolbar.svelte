<script lang="ts">
  /*
   * Dashboard editor toolbar. Editable name + save button + add-tile menu.
   * In Viewer mode (readOnly) the name is read-only text and the action
   * buttons are hidden.
   *
   * The name input is driven by a callback rather than $bindable so the
   * store stays the single source of truth — bypassing the store would
   * leave `dirty` out of sync.
   *
   * Add-tile menu is a scaffold — the chart / table / text / filter sub-
   * pickers land in task #13.
   */

  import AddTileMenu from "$lib/views/dashboard/AddTileMenu.svelte";
  import { Button } from "$lib/components/ui";
  import FilterSuggestionsModal from "$lib/views/dashboard/FilterSuggestionsModal.svelte";
  import PrefsMenu from "$lib/components/PrefsMenu.svelte";
  import type { TileType } from "$lib/api/dashboards";
  import { Monitor, RotateCcw, X, Share2, History, Undo2, Redo2, Menu, Download } from "lucide-svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  // #929: client-side PNG / PDF export of the current dashboard view.
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { exportDashboard, type ExportFormat } from "$lib/dashboard/dashboardExport";
  // #941 + #947 PR2 — share-link + version-history entry points.
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import DashboardShareModal from "$lib/views/dashboard/DashboardShareModal.svelte";
  import HistoryPanel from "$lib/views/dashboard/HistoryPanel.svelte";
  // #1175: collapse the action buttons into a hamburger on narrow widths
  // (restores the #932 behaviour lost in the #914/#915/#932 merge), now with
  // Undo/Redo folded into the collapse. Same breakpoint that stacks the grid.
  import { isNarrow, DEFAULT_STACK_BREAKPOINT } from "$lib/dashboard/responsiveLayout";

  interface Props {
    name: string;
    onNameChange?: (next: string) => void;
    tags?: string[];
    onTagsChange?: (next: string[]) => void;
    readOnly?: boolean;
    saving?: boolean;
    dirty?: boolean;
    onSave?: () => void;
    onAddTile?: (type: TileType) => void;
    /** Issue #927 — true iff any click-filter exists OR any panel
     *  widget's members[] differs from its saved default. Drives the
     *  Reset filters button's disabled state. */
    canResetFilters?: boolean;
    onResetFilters?: () => void;
    /** Issue #928 — enter presentation / fullscreen mode. Shown in both
     *  edit and viewer modes (TV-wall display is a viewer use case). */
    onPresent?: () => void;
    /** Issue #914 — undo / redo of structural edits. Buttons render in
     *  edit mode only; disabled when the respective stack is empty. */
    canUndo?: boolean;
    canRedo?: boolean;
    onUndo?: () => void;
    onRedo?: () => void;
    /** Issue #929 — the dashboard grid DOM node to rasterise for PNG / PDF
     *  export. Null while the grid is unmounted (empty-state guidance), in
     *  which case the Export action is disabled. Shown in both edit and
     *  viewer modes — exporting a shared view is a viewer use case. */
    gridElement?: HTMLElement | null;
  }

  let {
    name,
    onNameChange,
    tags = [],
    onTagsChange,
    readOnly = false,
    saving = false,
    dirty = false,
    onSave,
    onAddTile,
    canResetFilters = false,
    onResetFilters,
    onPresent,
    canUndo = false,
    canRedo = false,
    onUndo,
    onRedo,
    gridElement = null,
  }: Props = $props();

  // #941/#947: share + history act on the PERSISTED dashboard (one that
  // actually exists on the server), so the buttons only appear once it has been
  // fetched or saved — never for an unsaved draft or an AI-assembled review
  // that merely carries a suggested savedPath (SEC D2 FIX 1: gating on savedPath
  // let a pre-save Share mint a real public token for a not-yet-written path).
  // savedPath stays the Save TARGET; `persisted` gates the path-keyed surfaces.
  const savedPath = $derived(dashboardStore.savedPath);
  const persisted = $derived(dashboardStore.persisted);
  let shareOpen = $state(false);
  let historyOpen = $state(false);

  let suggestOpen = $state(false);
  let newTagInput = $state<string>("");

  // #929: Export action — a format picker (PNG / PDF) and an in-flight
  // flag so a long rasterise can't be re-triggered. The libraries
  // (html-to-image, jspdf) are dynamically imported inside the export
  // module so they don't bloat the toolbar's initial bundle.
  let exportMenuOpen = $state(false);
  let exporting = $state(false);
  let exportError = $state<string | null>(null);
  const canExport = $derived(!!gridElement);

  async function runExport(format: ExportFormat): Promise<void> {
    exportMenuOpen = false;
    menuOpen = false; // also close the hamburger if we were collapsed
    if (!gridElement || exporting) return;
    exporting = true;
    exportError = null;
    try {
      await exportDashboard(
        gridElement,
        { title: name, filters: activeFilters.all },
        format,
      );
    } catch (err) {
      exportError = err instanceof Error ? err.message : String(err);
      console.error("Dashboard export failed", err);
    } finally {
      exporting = false;
    }
  }

  // Close the export sub-menu on outside click / Escape. Mirrors the
  // hamburger's handler; the menu lives inside the header so sub-clicks
  // don't count as outside.
  $effect(() => {
    if (!exportMenuOpen) return;
    function onDocClick(e: MouseEvent): void {
      const t = e.target as Node | null;
      if (t && headerEl && !headerEl.contains(t)) exportMenuOpen = false;
    }
    function onKey(e: KeyboardEvent): void {
      if (e.key === "Escape") exportMenuOpen = false;
    }
    document.addEventListener("mousedown", onDocClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDocClick);
      document.removeEventListener("keydown", onKey);
    };
  });

  // #1175: responsive hamburger. A ResizeObserver on the header tracks its
  // width; below the stack breakpoint the secondary actions collapse into a
  // ☰ menu (Save stays visible). Mirrors DashboardGrid's observer pattern.
  let headerEl = $state<HTMLElement | null>(null);
  let narrow = $state(false);
  let menuOpen = $state(false);

  $effect(() => {
    const el = headerEl;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      narrow = isNarrow(entries[0]?.contentRect.width ?? el.clientWidth, DEFAULT_STACK_BREAKPOINT);
    });
    ro.observe(el);
    narrow = isNarrow(el.clientWidth, DEFAULT_STACK_BREAKPOINT);
    return () => ro.disconnect();
  });

  // Expanding back to wide closes the menu so it can't linger inline.
  $effect(() => {
    if (!narrow) menuOpen = false;
  });

  // Close the hamburger on outside click / Escape (sub-dropdowns like
  // Add-tile / theme render inside the header, so they don't count as
  // "outside").
  $effect(() => {
    if (!menuOpen) return;
    function onDocClick(e: MouseEvent): void {
      const t = e.target as Node | null;
      if (t && headerEl && !headerEl.contains(t)) menuOpen = false;
    }
    function onKey(e: KeyboardEvent): void {
      if (e.key === "Escape") menuOpen = false;
    }
    document.addEventListener("mousedown", onDocClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDocClick);
      document.removeEventListener("keydown", onKey);
    };
  });

  // The input is controlled by the `name` prop directly — the store is
  // the source of truth. onNameChange propagates user edits upstream and
  // the prop refreshes on the next render.
  function handleNameInput(e: Event): void {
    onNameChange?.((e.target as HTMLInputElement).value);
  }

  /** Commit the new-tag input. Trims whitespace; ignores empty / dupes
   *  (the store de-dupes too, this is just to keep the chip rendering
   *  intuitive). Splits on comma so users can paste "a, b, c". */
  function commitNewTag(): void {
    const raw = newTagInput.trim();
    if (!raw) return;
    const incoming = raw
      .split(",")
      .map((s) => s.trim())
      .filter((s) => s.length > 0 && !tags.includes(s));
    if (incoming.length === 0) {
      newTagInput = "";
      return;
    }
    onTagsChange?.([...tags, ...incoming]);
    newTagInput = "";
  }

  function removeTag(t: string): void {
    onTagsChange?.(tags.filter((x) => x !== t));
  }

  function handleTagInputKeydown(e: KeyboardEvent): void {
    if (e.key === "Enter") {
      e.preventDefault();
      commitNewTag();
    } else if (e.key === "Backspace" && newTagInput === "" && tags.length > 0) {
      // Backspace on empty input removes the last chip — standard chip-
      // picker UX.
      e.preventDefault();
      removeTag(tags[tags.length - 1]);
    }
  }
</script>

<header
  class="toolbar"
  class:toolbar--narrow={narrow}
  role="toolbar"
  aria-label="Dashboard toolbar"
  bind:this={headerEl}
>
  <div class="flex flex-col gap-1 min-w-0">
    {#if readOnly}
      <h1 class="text-lg font-semibold m-0">{name}</h1>
    {:else}
      <input
        class="name"
        type="text"
        value={name}
        oninput={handleNameInput}
        placeholder="Untitled dashboard"
        aria-label="Dashboard name"
      />
    {/if}

    {#if readOnly}
      {#if tags.length > 0}
        <div class="flex flex-wrap gap-1 items-center pl-2" aria-label="Dashboard tags">
          {#each tags as t (t)}
            <span class="tag-chip">{t}</span>
          {/each}
        </div>
      {/if}
    {:else}
      <div class="flex flex-wrap gap-1 items-center pl-2" aria-label="Dashboard tags">
        {#each tags as t (t)}
          <span class="tag-chip">
            {t}
            <button
              type="button"
              class="tag-remove"
              onclick={() => removeTag(t)}
              aria-label="Remove tag {t}"
              title="Remove tag"
            >
              <X size={10} />
            </button>
          </span>
        {/each}
        <input
          class="tag-input"
          type="text"
          bind:value={newTagInput}
          onkeydown={handleTagInputKeydown}
          onblur={commitNewTag}
          placeholder={tags.length === 0 ? "Add tags…" : "Add tag"}
          aria-label="Add tag"
        />
      </div>
    {/if}
  </div>

  <div class="flex-1"></div>

  <!-- #1175: wide → all actions inline; narrow → Save stays out, the rest
       (incl. Undo/Redo) collapse into a ☰ menu. Both render the SAME
       snippets, so there's no duplication and nothing falls outside the
       collapse. -->
  <div class="actions">
    {#if narrow}
      {@render saveButton()}
      <Button variant="outline" class="hamburger" aria-label="More actions" aria-haspopup="menu" aria-expanded={menuOpen} title="More actions" onclick={() => (menuOpen = !menuOpen)}>
        <Menu size={16} aria-hidden="true" />
      </Button>
      {#if menuOpen}
        <div class="actions-menu" role="menu">
          {@render secondaryActions()}
        </div>
      {/if}
    {:else}
      {@render secondaryActions()}
      {@render saveButton()}
    {/if}
  </div>
</header>

{#snippet secondaryActions()}
  {#if onPresent}
    <Button variant="outline" onclick={() => onPresent?.()} title="Present — fullscreen, hide chrome (press F, Esc to exit)" aria-label="Present">
      <Monitor size={14} aria-hidden="true" />
      <span>Present</span>
    </Button>
  {/if}

  <!-- #929: Export the current dashboard view (PNG / PDF). A small format
       picker anchors under the button; disabled until the grid mounts. -->
  <div class="relative inline-flex">
    <Button variant="outline" onclick={() => (exportMenuOpen = !exportMenuOpen)} disabled={!canExport || exporting} aria-disabled={!canExport || exporting} aria-haspopup="menu" aria-expanded={exportMenuOpen} title={i18n.t("dashboard.export.title", "Export the current view as PNG or PDF")} aria-label={i18n.t("dashboard.export", "Export")}>
      <Download size={14} aria-hidden="true" />
      <span>{exporting ? i18n.t("dashboard.export.busy", "Exporting…") : i18n.t("dashboard.export", "Export")}</span>
    </Button>
    {#if exportMenuOpen}
      <div class="export-menu" role="menu" aria-label={i18n.t("dashboard.export.formatLabel", "Export format")}>
        <Button variant="outline" class="export-option" role="menuitem" onclick={() => runExport("png")}>
          {i18n.t("dashboard.export.png", "PNG image")}
        </Button>
        <Button variant="outline" class="export-option" role="menuitem" onclick={() => runExport("pdf")}>
          {i18n.t("dashboard.export.pdf", "PDF document")}
        </Button>
      </div>
    {/if}
  </div>

  {#if !readOnly}
    <div class="undo-redo" role="group" aria-label={i18n.t("dashboard.history.group", "Undo and redo")}>
      <Button variant="outline" class="icon-only" onclick={() => onUndo?.()} disabled={!canUndo || !onUndo} aria-disabled={!canUndo || !onUndo} title={i18n.t("dashboard.undo.title", "Undo (Ctrl/Cmd+Z)")} aria-label={i18n.t("dashboard.undo", "Undo")}>
        <Undo2 size={14} aria-hidden="true" />
      </Button>
      <Button variant="outline" class="icon-only" onclick={() => onRedo?.()} disabled={!canRedo || !onRedo} aria-disabled={!canRedo || !onRedo} title={i18n.t("dashboard.redo.title", "Redo (Ctrl/Cmd+Shift+Z)")} aria-label={i18n.t("dashboard.redo", "Redo")}>
        <Redo2 size={14} aria-hidden="true" />
      </Button>
    </div>

    <Button variant="outline" onclick={() => (suggestOpen = true)} aria-haspopup="dialog" title="Suggest filter widgets from dimensions your tiles already use">
      🔍 Suggest filters
    </Button>

    <Button variant="outline" onclick={() => onResetFilters?.()} disabled={!canResetFilters || !onResetFilters} aria-disabled={!canResetFilters || !onResetFilters} title={canResetFilters ? "Clear all click-filters and restore panel filters to their saved defaults" : "No active filters to reset"}>
      <RotateCcw size={14} aria-hidden="true" />
      <span>Reset filters</span>
    </Button>

    <AddTileMenu onPick={(t) => onAddTile?.(t)} disabled={!onAddTile} />
  {/if}

  {#if persisted}
    <Button variant="outline" onclick={() => (historyOpen = true)} title="Version history — preview and restore earlier saves" aria-haspopup="dialog">
      <History size={14} aria-hidden="true" />
      <span>History</span>
    </Button>
    <Button variant="outline" onclick={() => (shareOpen = true)} title="Share a read-only link to this dashboard" aria-haspopup="dialog">
      <Share2 size={14} aria-hidden="true" />
      <span>Share</span>
    </Button>
  {/if}

  <!-- saiku#1050: theme (dark/light/system) + language control. -->
  <PrefsMenu placement="down" />
{/snippet}

{#snippet saveButton()}
  {#if !readOnly}
    <Button onclick={() => onSave?.()} disabled={saving || !dirty} aria-disabled={saving || !dirty}>
      {#if saving}
        Saving…
      {:else if dirty}
        Save
      {:else}
        Saved
      {/if}
    </Button>
  {/if}
{/snippet}

{#if persisted}
  <DashboardShareModal dashboardPath={savedPath} open={shareOpen} onClose={() => (shareOpen = false)} />
  <HistoryPanel
    dashboardPath={savedPath}
    open={historyOpen}
    onClose={() => (historyOpen = false)}
    onRestored={() => void dashboardStore.load(savedPath)}
  />
{/if}

<FilterSuggestionsModal open={suggestOpen} onClose={() => (suggestOpen = false)} />

{#if exportError}
  <div class="export-error" role="alert">
    {i18n.t("dashboard.export.failed", "Export failed")}: {exportError}
    <button type="button" class="bg-none border-0 p-0 text-inherit cursor-pointer inline-flex items-center" onclick={() => (exportError = null)} aria-label="Dismiss">
      <X size={12} />
    </button>
  </div>
{/if}

<style>
.toolbar {
    display: flex;
    align-items: flex-start;
    gap: 0.75rem;
    padding: 0.5rem 0.25rem;
    border-bottom: 1px solid var(--border);
  }
  .name {
    font-size: 1.125rem;
    font-weight: var(--weight-semibold);
    padding: 0.375rem 0.5rem;
    border: 1px solid transparent;
    border-radius: 4px;
    background: transparent;
    min-width: 18rem;
  }
  .name:hover, .name:focus {
    border-color: var(--border-strong);
    background: var(--bg);
    outline: none;
  }
  .tag-chip {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    padding: 0.125rem 0.5rem;
    border-radius: 999px;
    background: var(--bg-subtle);
    color: var(--fg);
    font-size: 0.6875rem;
    line-height: 1.4;
  }
  .tag-remove {
    background: none;
    border: none;
    padding: 0;
    color: var(--fg-muted);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
  }
  .tag-remove:hover {
    color: var(--danger);
  }
  .tag-input {
    border: 1px dashed transparent;
    background: transparent;
    padding: 0.125rem 0.375rem;
    font-size: 0.6875rem;
    min-width: 6rem;
    color: var(--fg);
  }
  .tag-input:hover, .tag-input:focus {
    border-color: var(--border-strong);
    outline: none;
  }
  .actions {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    position: relative; /* anchor the #1175 hamburger dropdown */
  }
  /* #1175: narrow toolbar — let the name shrink so Save + ☰ always fit. */
  .toolbar--narrow .name { min-width: 0; }
  /* #1175: hamburger dropdown holding the collapsed secondary actions. */
  .actions-menu {
    position: absolute;
    top: 100%;
    right: 0;
    margin-top: 4px;
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 0.25rem;
    min-width: 12rem;
    padding: 0.375rem;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
    z-index: 60;
  }
  /* Stack the collapsed buttons full-width, left-aligned, like a menu. The
     undo/redo group lays its two buttons out in a row. */
  .actions-menu :global(.btn) {
    width: 100%;
    justify-content: flex-start;
  }
  .actions-menu .undo-redo {
    display: flex;
  }
  /* #929: the export wrapper stretches full-width inside the hamburger so
     its button matches the other collapsed actions; the format dropdown
     still anchors to it. */
  .actions-menu .undo-redo :global(.btn) {
    width: auto;
    flex: 1;
    justify-content: center;
  }
  .undo-redo {
    display: inline-flex;
    gap: 0.25rem;
    align-items: center;
  }
  /* #929: Export button + format picker. The wrapper anchors the dropdown;
     inside the hamburger it stretches full-width like the other collapsed
     actions (the .actions-menu :global(.btn) rule handles the button). */
  .export-menu {
    position: absolute;
    top: 100%;
    left: 0;
    margin-top: 4px;
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 0.25rem;
    min-width: 10rem;
    padding: 0.375rem;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
    z-index: 70;
  }
  .export-error {
    position: fixed;
    bottom: 1rem;
    right: 1rem;
    z-index: 80;
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    max-width: 24rem;
    padding: 0.5rem 0.75rem;
    background: color-mix(in srgb, var(--danger) 14%, var(--bg));
    color: var(--danger);
    border: 1px solid var(--danger);
    border-radius: 6px;
    font-size: 0.8125rem;
  }
</style>

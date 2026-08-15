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
  // #1610: fold the secondary toolbar actions into an overflow "⋯ More" menu,
  // reusing the same ContextMenu primitive the tiles / dashboards list use.
  import ContextMenu from "$lib/components/ContextMenu.svelte";
  import type { TileType } from "$lib/api/dashboards";
  import { Monitor, RotateCcw, X, Share2, History, Undo2, Redo2, Menu, MoreHorizontal, Download } from "@lucide/svelte";
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

  // #1610: shape of a ContextMenu entry (mirrors ContextMenu.svelte's item
  // type; declared inline like QueryCanvas rather than imported from a
  // component's instance script).
  type ContextMenuItem = { id: string; label: string; disabled?: boolean; danger?: boolean; sep?: boolean };

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
    moreOpen = false; // …and the #1610 overflow menu
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

  // #1610: overflow "⋯ More" menu (wide layout only). The primary actions
  // (Present, undo/redo, Add tile, Export, Save) stay inline; the secondary
  // ones (Suggest / Reset filters, History, Share) fold in here so the
  // toolbar reads less cluttered. Reuses the shared ContextMenu primitive
  // (fixed-positioned at moreX/moreY, anchored to the trigger's bottom-right).
  const MORE_MENU_WIDTH = 200; // px — keep in step with ContextMenu min-width
  let moreOpen = $state(false);
  let moreX = $state(0);
  let moreY = $state(0);

  // Single source of truth for the overflow entries. Export stays inline, so
  // the overflow holds only the secondary filter / dashboard actions. Disabled
  // / visibility mirror the inline buttons exactly so behaviour is unchanged —
  // only the location moves.
  const moreItems: ContextMenuItem[] = $derived.by(() => {
    const items: ContextMenuItem[] = [];
    if (!readOnly) {
      items.push({ id: "suggest", label: "Suggest filters" });
      items.push({ id: "reset", label: "Reset filters", disabled: !canResetFilters || !onResetFilters });
    }
    if (persisted) {
      if (items.length > 0) items.push({ id: "_sep-dash", label: "", sep: true });
      items.push({ id: "history", label: "Version history" });
      items.push({ id: "share", label: "Share link" });
    }
    return items;
  });

  // Toggle the overflow menu, anchoring it under the trigger's right edge.
  // stopPropagation keeps this click from reaching ContextMenu's window
  // listener, so re-clicking the trigger toggles cleanly instead of the
  // outside-click handler racing the toggle.
  function toggleMore(e: MouseEvent): void {
    e.stopPropagation();
    if (moreOpen) {
      moreOpen = false;
      return;
    }
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    moreX = rect.right - MORE_MENU_WIDTH;
    moreY = rect.bottom + 4;
    moreOpen = true;
  }

  // Route an overflow pick to the same handler the inline button used.
  function onMorePick(id: string): void {
    switch (id) {
      case "suggest": suggestOpen = true; break;
      case "reset": onResetFilters?.(); break;
      case "history": historyOpen = true; break;
      case "share": shareOpen = true; break;
    }
    moreOpen = false;
  }

  // Close the overflow menu on Escape (ContextMenu already handles the
  // outside-click; this matches the hamburger / export-menu polish).
  $effect(() => {
    if (!moreOpen) return;
    function onKey(e: KeyboardEvent): void {
      if (e.key === "Escape") moreOpen = false;
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
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
  <div class="title-block flex flex-col gap-1 min-w-0">
    {#if readOnly}
      <h1 class="dash-title text-lg font-semibold m-0">{name}</h1>
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

  <!-- Wide → primary actions inline + a "⋯ More" overflow menu for the
       secondary ones (#1610); narrow → Save stays out and everything else
       (primary + secondary) collapses into the ☰ hamburger (#1175). Both
       widths render the SAME primary snippet, so nothing falls outside the
       responsive collapse. -->
  <div class="actions">
    {#if narrow}
      {@render saveButton()}
      <Button variant="outline" class="hamburger" aria-label="More actions" aria-haspopup="menu" aria-expanded={menuOpen} title="More actions" onclick={() => (menuOpen = !menuOpen)}>
        <Menu size={16} aria-hidden="true" />
      </Button>
      {#if menuOpen}
        <div class="actions-menu" role="menu">
          {@render primaryActions()}
          {@render secondaryActions()}
        </div>
      {/if}
    {:else}
      {@render primaryActions()}
      <!-- #1610: secondary actions live behind this overflow menu. -->
      <Button variant="outline" class="icon-only" aria-label="More actions" aria-haspopup="menu" aria-expanded={moreOpen} title="More actions" onclick={toggleMore}>
        <MoreHorizontal size={16} aria-hidden="true" />
      </Button>
      <!-- saiku#1050: theme (dark/light/system) + language control. -->
      <PrefsMenu placement="down" />
      {@render saveButton()}
    {/if}
  </div>
</header>

<!-- #1610: PRIMARY actions — kept inline at wide widths (and rendered first
     inside the narrow hamburger). Present, undo/redo, Add tile and Export. -->
{#snippet primaryActions()}
  {#if onPresent}
    <Button variant="outline" onclick={() => onPresent?.()} title="Present — fullscreen, hide chrome (press F, Esc to exit)" aria-label="Present">
      <Monitor size={14} aria-hidden="true" />
      <span>Present</span>
    </Button>
  {/if}

  {#if !readOnly}
    <div class="undo-redo" role="group" aria-label={i18n.t("dashboard.history.group", "Undo and redo")}>
      <Button variant="outline" class="icon-only" onclick={() => onUndo?.()} disabled={!canUndo || !onUndo} aria-disabled={!canUndo || !onUndo} title={i18n.t("dashboard.undo.title", "Undo (Ctrl/Cmd+Z)")} aria-label={i18n.t("dashboard.undo", "Undo")}>
        <Undo2 size={14} aria-hidden="true" />
      </Button>
      <Button variant="outline" class="icon-only" onclick={() => onRedo?.()} disabled={!canRedo || !onRedo} aria-disabled={!canRedo || !onRedo} title={i18n.t("dashboard.redo.title", "Redo (Ctrl/Cmd+Shift+Z)")} aria-label={i18n.t("dashboard.redo", "Redo")}>
        <Redo2 size={14} aria-hidden="true" />
      </Button>
    </div>

    <AddTileMenu onPick={(t) => onAddTile?.(t)} disabled={!onAddTile} />
  {/if}

  <!-- #929/#1610: Export the current dashboard view (PNG / PDF) stays INLINE.
       A small format picker anchors under the button; disabled until the grid
       mounts. Shown in both edit and viewer modes. The inline "Exporting…"
       busy label lives on the button while a rasterise is in flight. -->
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
{/snippet}

<!-- #1610: SECONDARY actions — folded behind the "⋯ More" overflow at wide
     widths (see moreItems / onMorePick); rendered as full buttons only inside
     the narrow (#1175) hamburger, where everything already collapses. The
     wide overflow drives the SAME handlers, so behaviour is unchanged. -->
{#snippet secondaryActions()}
  {#if !readOnly}
    <Button variant="outline" onclick={() => (suggestOpen = true)} aria-haspopup="dialog" title="Suggest filter widgets from dimensions your tiles already use">
      🔍 Suggest filters
    </Button>

    <Button variant="outline" onclick={() => onResetFilters?.()} disabled={!canResetFilters || !onResetFilters} aria-disabled={!canResetFilters || !onResetFilters} title={canResetFilters ? "Clear all click-filters and restore panel filters to their saved defaults" : "No active filters to reset"}>
      <RotateCcw size={14} aria-hidden="true" />
      <span>Reset filters</span>
    </Button>
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

<!-- #1610: the "⋯ More" overflow menu (wide layout). Anchored at moreX/moreY. -->
<ContextMenu
  open={moreOpen}
  x={moreX}
  y={moreY}
  items={moreItems}
  onPick={onMorePick}
  onClose={() => (moreOpen = false)}
/>

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
    border-bottom: 1px solid hsl(var(--border));
  }
  /* #1604: the title block absorbs the toolbar's free space (it replaces the
     old flex spacer) so the editable name / heading has room to show the full
     dashboard title instead of being clipped at a fixed 18rem. */
  .title-block {
    flex: 1 1 auto;
    min-width: 0;
  }
  .name {
    font-size: 1.125rem;
    font-weight: var(--weight-semibold);
    padding: 0.375rem 0.5rem;
    border: 1px solid transparent;
    border-radius: 4px;
    background: transparent;
    /* #1604: fill the (now-growing) title block; keep a sensible floor and a
       cap so the field doesn't stretch edge-to-edge on very wide screens. */
    width: 100%;
    min-width: 18rem;
    max-width: 44rem;
  }
  /* #1604: let the read-only heading use the block's full width and wrap
     rather than clip when a long title can't fit on one line. */
  .dash-title {
    min-width: 0;
    max-width: 44rem;
    overflow-wrap: anywhere;
  }
  .name:hover, .name:focus {
    border-color: hsl(var(--border-strong));
    background: hsl(var(--bg));
    outline: none;
  }
  .tag-chip {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    padding: 0.125rem 0.5rem;
    border-radius: 999px;
    background: hsl(var(--bg-subtle));
    color: hsl(var(--fg));
    font-size: 0.6875rem;
    line-height: 1.4;
  }
  .tag-remove {
    background: none;
    border: none;
    padding: 0;
    color: hsl(var(--fg-muted));
    cursor: pointer;
    display: inline-flex;
    align-items: center;
  }
  .tag-remove:hover {
    color: hsl(var(--danger));
  }
  .tag-input {
    border: 1px dashed transparent;
    background: transparent;
    padding: 0.125rem 0.375rem;
    font-size: 0.6875rem;
    min-width: 6rem;
    color: hsl(var(--fg));
  }
  .tag-input:hover, .tag-input:focus {
    border-color: hsl(var(--border-strong));
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
    background: hsl(var(--bg));
    border: 1px solid hsl(var(--border));
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
    background: hsl(var(--bg));
    border: 1px solid hsl(var(--border));
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
    background: color-mix(in srgb, hsl(var(--danger)) 14%, hsl(var(--bg)));
    color: hsl(var(--danger));
    border: 1px solid hsl(var(--danger));
    border-radius: 6px;
    font-size: 0.8125rem;
  }
</style>

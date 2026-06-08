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
  import FilterSuggestionsModal from "$lib/views/dashboard/FilterSuggestionsModal.svelte";
  import PrefsMenu from "$lib/components/PrefsMenu.svelte";
  import type { TileType } from "$lib/api/dashboards";
  import { Monitor, RotateCcw, X, Share2, History, Undo2, Redo2, Menu } from "lucide-svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
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
  }: Props = $props();

  // #941/#947: share + history act on the SAVED dashboard, so the buttons only
  // appear once it has a repository path (not for an unsaved draft).
  const savedPath = $derived(dashboardStore.savedPath);
  let shareOpen = $state(false);
  let historyOpen = $state(false);

  let suggestOpen = $state(false);
  let newTagInput = $state<string>("");

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
  <div class="title-block">
    {#if readOnly}
      <h1 class="name-readonly">{name}</h1>
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
        <div class="tags" aria-label="Dashboard tags">
          {#each tags as t (t)}
            <span class="tag-chip">{t}</span>
          {/each}
        </div>
      {/if}
    {:else}
      <div class="tags" aria-label="Dashboard tags">
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

  <div class="spacer"></div>

  <!-- #1175: wide → all actions inline; narrow → Save stays out, the rest
       (incl. Undo/Redo) collapse into a ☰ menu. Both render the SAME
       snippets, so there's no duplication and nothing falls outside the
       collapse. -->
  <div class="actions">
    {#if narrow}
      {@render saveButton()}
      <button
        type="button"
        class="btn hamburger"
        aria-label="More actions"
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        title="More actions"
        onclick={() => (menuOpen = !menuOpen)}
      >
        <Menu size={16} aria-hidden="true" />
      </button>
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
    <button
      type="button"
      class="btn"
      onclick={() => onPresent?.()}
      title="Present — fullscreen, hide chrome (press F, Esc to exit)"
      aria-label="Present"
    >
      <Monitor size={14} aria-hidden="true" />
      <span>Present</span>
    </button>
  {/if}

  {#if !readOnly}
    <div class="undo-redo" role="group" aria-label={i18n.t("dashboard.history.group", "Undo and redo")}>
      <button
        type="button"
        class="btn icon-only"
        onclick={() => onUndo?.()}
        disabled={!canUndo || !onUndo}
        aria-disabled={!canUndo || !onUndo}
        title={i18n.t("dashboard.undo.title", "Undo (Ctrl/Cmd+Z)")}
        aria-label={i18n.t("dashboard.undo", "Undo")}
      >
        <Undo2 size={14} aria-hidden="true" />
      </button>
      <button
        type="button"
        class="btn icon-only"
        onclick={() => onRedo?.()}
        disabled={!canRedo || !onRedo}
        aria-disabled={!canRedo || !onRedo}
        title={i18n.t("dashboard.redo.title", "Redo (Ctrl/Cmd+Shift+Z)")}
        aria-label={i18n.t("dashboard.redo", "Redo")}
      >
        <Redo2 size={14} aria-hidden="true" />
      </button>
    </div>

    <button
      type="button"
      class="btn"
      onclick={() => (suggestOpen = true)}
      aria-haspopup="dialog"
      title="Suggest filter widgets from dimensions your tiles already use"
    >
      🔍 Suggest filters
    </button>

    <button
      type="button"
      class="btn"
      onclick={() => onResetFilters?.()}
      disabled={!canResetFilters || !onResetFilters}
      aria-disabled={!canResetFilters || !onResetFilters}
      title={canResetFilters
        ? "Clear all click-filters and restore panel filters to their saved defaults"
        : "No active filters to reset"}
    >
      <RotateCcw size={14} aria-hidden="true" />
      <span>Reset filters</span>
    </button>

    <AddTileMenu onPick={(t) => onAddTile?.(t)} disabled={!onAddTile} />
  {/if}

  {#if savedPath}
    <button
      type="button"
      class="btn"
      onclick={() => (historyOpen = true)}
      title="Version history — preview and restore earlier saves"
      aria-haspopup="dialog"
    >
      <History size={14} aria-hidden="true" />
      <span>History</span>
    </button>
    <button
      type="button"
      class="btn"
      onclick={() => (shareOpen = true)}
      title="Share a read-only link to this dashboard"
      aria-haspopup="dialog"
    >
      <Share2 size={14} aria-hidden="true" />
      <span>Share</span>
    </button>
  {/if}

  <!-- saiku#1050: theme (dark/light/system) + language control. -->
  <PrefsMenu placement="down" />
{/snippet}

{#snippet saveButton()}
  {#if !readOnly}
    <button
      type="button"
      class="btn primary"
      onclick={() => onSave?.()}
      disabled={saving || !dirty}
      aria-disabled={saving || !dirty}
    >
      {#if saving}
        Saving…
      {:else if dirty}
        Save
      {:else}
        Saved
      {/if}
    </button>
  {/if}
{/snippet}

{#if savedPath}
  <DashboardShareModal dashboardPath={savedPath} open={shareOpen} onClose={() => (shareOpen = false)} />
  <HistoryPanel
    dashboardPath={savedPath}
    open={historyOpen}
    onClose={() => (historyOpen = false)}
    onRestored={() => void dashboardStore.load(savedPath)}
  />
{/if}

<FilterSuggestionsModal open={suggestOpen} onClose={() => (suggestOpen = false)} />

<style>
  .toolbar {
    display: flex;
    align-items: flex-start;
    gap: 0.75rem;
    padding: 0.5rem 0.25rem;
    border-bottom: 1px solid var(--border);
  }
  .title-block {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    min-width: 0;
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
  .name-readonly {
    font-size: 1.125rem;
    font-weight: var(--weight-semibold);
    margin: 0;
  }
  .tags {
    display: flex;
    flex-wrap: wrap;
    gap: 0.25rem;
    align-items: center;
    padding-left: 0.5rem;
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
  .spacer { flex: 1; }
  .actions {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    position: relative; /* anchor the #1175 hamburger dropdown */
  }
  /* #1175: narrow toolbar — let the name shrink so Save + ☰ always fit. */
  .toolbar--narrow .name { min-width: 0; }
  .hamburger { padding: 0.375rem 0.5rem; }
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
  .actions-menu .undo-redo :global(.btn) {
    width: auto;
    flex: 1;
    justify-content: center;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    padding: 0.375rem 0.75rem;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.875rem;
  }
  .btn:disabled { opacity: 0.5; cursor: not-allowed; }
  .undo-redo {
    display: inline-flex;
    gap: 0.25rem;
    align-items: center;
  }
  .btn.icon-only {
    padding: 0.375rem 0.5rem;
  }
  .btn.primary {
    background: var(--accent);
    color: white;
    border-color: var(--accent);
  }
  .btn.primary:disabled {
    /* Saved state — keep it visually distinct from a destructive disable. */
    opacity: 0.7;
  }
</style>

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
  import { Monitor, RotateCcw, X, Share2, History, Undo2, Redo2 } from "lucide-svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  // #941 + #947 PR2 — share-link + version-history entry points.
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import DashboardShareModal from "$lib/views/dashboard/DashboardShareModal.svelte";
  import HistoryPanel from "$lib/views/dashboard/HistoryPanel.svelte";

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

<header class="toolbar" role="toolbar" aria-label="Dashboard toolbar">
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

  <div class="actions">
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

    <!-- saiku#1050: theme (dark/light/system) + language control, parity with
         the workspace. Shown in both editor and viewer; the whole toolbar is
         hidden in presentation mode (#928), so it disappears on TV walls. -->
    <PrefsMenu placement="down" />
  </div>
</header>

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

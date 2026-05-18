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
  import type { TileType } from "$lib/api/dashboards";

  interface Props {
    name: string;
    onNameChange?: (next: string) => void;
    readOnly?: boolean;
    saving?: boolean;
    dirty?: boolean;
    onSave?: () => void;
    onAddTile?: (type: TileType) => void;
  }

  let {
    name,
    onNameChange,
    readOnly = false,
    saving = false,
    dirty = false,
    onSave,
    onAddTile,
  }: Props = $props();

  let suggestOpen = $state(false);

  // The input is controlled by the `name` prop directly — the store is
  // the source of truth. onNameChange propagates user edits upstream and
  // the prop refreshes on the next render.
  function handleNameInput(e: Event): void {
    onNameChange?.((e.target as HTMLInputElement).value);
  }
</script>

<header class="toolbar" role="toolbar" aria-label="Dashboard toolbar">
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

  <div class="spacer"></div>

  {#if !readOnly}
    <div class="actions">
      <button
        type="button"
        class="btn"
        onclick={() => (suggestOpen = true)}
        aria-haspopup="dialog"
        title="Suggest filter widgets from dimensions your tiles already use"
      >
        🔍 Suggest filters
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
    </div>
  {/if}
</header>

<FilterSuggestionsModal open={suggestOpen} onClose={() => (suggestOpen = false)} />

<style>
  .toolbar {
    display: flex;
    align-items: center;
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
  .name-readonly {
    font-size: 1.125rem;
    font-weight: var(--weight-semibold);
    margin: 0;
  }
  .spacer { flex: 1; }
  .actions {
    display: flex;
    gap: 0.5rem;
    align-items: center;
  }
  .btn {
    padding: 0.375rem 0.75rem;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.875rem;
  }
  .btn:disabled { opacity: 0.5; cursor: not-allowed; }
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

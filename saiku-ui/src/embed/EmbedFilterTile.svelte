<script lang="ts">
  /*
   * Filter tile for the embed dashboard bundle. Renders a dropdown / multi-select
   * populated from GET /embed/dashboard/{path}/tile/{id}/members and emits a
   * selection object the surrounding EmbedDashboard picks up to drive dependent
   * chart / kpi tiles.
   *
   * Widget shapes (from tile.widget):
   *   "single-select" — one member picked at a time (undefined = clear).
   *   "multi-select"   — 0..N members, default.
   *
   * Empty selection = clear the filter (server-side merge drops the slicer).
   */
  import { fetchTileMembers, EmbedFetchError, type EmbedFilterOverride, type EmbedMember } from "./api";
  import type { EmbedDashboardTile } from "./types";

  interface Props {
    server: string;
    token: string;
    dashboardPath: string;
    tile: EmbedDashboardTile;
    onChange: (override: EmbedFilterOverride | null) => void;
  }

  let { server, token, dashboardPath, tile, onChange }: Props = $props();

  let members = $state<EmbedMember[] | null>(null);
  let loadError = $state<string | null>(null);
  let selected = $state<Set<string>>(new Set());
  let widget = $derived((tile.widget || "multi-select").toLowerCase());

  $effect(() => {
    const s = server.trim();
    const p = dashboardPath.trim();
    if (!s || !p || !tile.target) {
      members = null;
      return;
    }
    let cancelled = false;
    members = null;
    loadError = null;
    fetchTileMembers(s, p, tile.id, token || undefined)
      .then((list) => {
        if (cancelled) return;
        members = list;
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        loadError = e instanceof EmbedFetchError ? (e.body.error ?? "Members unavailable") : "Members unavailable";
      });
    return () => {
      cancelled = true;
    };
  });

  function emit(): void {
    if (!tile.target) return;
    const pickedUniqueNames = Array.from(selected);
    if (pickedUniqueNames.length === 0) {
      onChange(null);
      return;
    }
    onChange({
      dimension: tile.target.dimension,
      hierarchy: tile.target.hierarchy ?? null,
      level: tile.target.level,
      members: pickedUniqueNames,
    });
  }

  function onSingleChange(e: Event): void {
    const value = (e.target as HTMLSelectElement).value;
    selected = value ? new Set([value]) : new Set();
    emit();
  }

  function onCheckboxChange(uniqueName: string, checked: boolean): void {
    const next = new Set(selected);
    if (checked) next.add(uniqueName);
    else next.delete(uniqueName);
    selected = next;
    emit();
  }

  function clearAll(): void {
    selected = new Set();
    emit();
  }
</script>

<div class="filter-tile">
  {#if !tile.target}
    <div class="state muted">Filter tile has no target</div>
  {:else if loadError}
    <div class="state error">{loadError}</div>
  {:else if members === null}
    <div class="state muted">Loading members…</div>
  {:else if members.length === 0}
    <div class="state muted">No members</div>
  {:else if widget === "single-select"}
    <select onchange={onSingleChange} aria-label={tile.title ?? tile.target.level}>
      <option value="">All {tile.target.level}</option>
      {#each members as m (m.uniqueName)}
        <option value={m.uniqueName} selected={selected.has(m.uniqueName)}>{m.caption}</option>
      {/each}
    </select>
  {:else}
    <div class="multi">
      {#each members as m (m.uniqueName)}
        <label class="checkbox">
          <input
            type="checkbox"
            checked={selected.has(m.uniqueName)}
            onchange={(e) => onCheckboxChange(m.uniqueName, (e.target as HTMLInputElement).checked)}
          />
          <span>{m.caption}</span>
        </label>
      {/each}
      {#if selected.size > 0}
        <button type="button" class="clear" onclick={clearAll}>Clear ({selected.size})</button>
      {/if}
    </div>
  {/if}
</div>

<style>
  .filter-tile {
    padding: 8px 10px;
    font-family: system-ui, sans-serif;
    font-size: 13px;
    color: var(--saiku-embed-fg, #1f2937);
  }
  .state {
    padding: 4px 0;
  }
  .state.muted {
    color: var(--saiku-embed-muted, #6b7280);
  }
  .state.error {
    color: var(--saiku-embed-error, #b91c1c);
  }
  select {
    width: 100%;
    padding: 4px 6px;
    font-size: 13px;
    border: 1px solid var(--saiku-embed-border, #e5e7eb);
    border-radius: 4px;
    background: var(--saiku-embed-bg, #ffffff);
    color: inherit;
  }
  .multi {
    display: flex;
    flex-direction: column;
    gap: 4px;
    max-height: 220px;
    overflow-y: auto;
  }
  .checkbox {
    display: flex;
    align-items: center;
    gap: 6px;
    cursor: pointer;
    padding: 2px 0;
  }
  .checkbox input {
    margin: 0;
  }
  .clear {
    margin-top: 4px;
    padding: 3px 8px;
    font-size: 12px;
    background: transparent;
    border: 1px solid var(--saiku-embed-border, #e5e7eb);
    border-radius: 4px;
    color: var(--saiku-embed-fg, #1f2937);
    cursor: pointer;
  }
  .clear:hover {
    background: var(--saiku-embed-row-hover, #f3f4f6);
  }
</style>

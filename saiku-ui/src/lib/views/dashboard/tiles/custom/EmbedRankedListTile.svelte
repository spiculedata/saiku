<script lang="ts">
  /*
   * Embed variant of the `ranked-list` custom tile (saiku#1441).
   * Token-scoped, read-only, self-contained: it renders inside the
   * <saiku-embed/> bundle, so it uses ONLY relative imports (no `$lib` alias)
   * and takes its data as the `rows` prop that <EmbedGrid> fetches through the
   * guarded per-tile embed query path.
   *
   * No chart library at all — the card is plain markup, so the embed bundle
   * pays nothing for it. Config is validated with the SAME validator the in-app
   * tile uses (rankedList.ts is pure — no app stores, no `$lib`).
   */

  import {
    projectRankedList,
    validateRankedListConfig,
    type RankedListConfig,
  } from "../../../../dashboard/custom/rankedList";

  /** Minimal cell shape (matches the embed EmbedCell) — kept local so this
   *  component doesn't reach into the embed module graph. */
  interface Cell {
    value: number | null;
    formatted: string;
  }
  type Row = Record<string, Cell | string>;

  interface Props {
    tile: { custom?: { renderer: string; options?: Record<string, unknown> } };
    /** Token-scoped rows from <EmbedGrid>. undefined/null = still loading. */
    rows?: Row[] | null;
  }

  let { tile, rows }: Props = $props();

  let validation = $derived(validateRankedListConfig(tile.custom?.options));
  let config = $derived<RankedListConfig>(
    validation.ok ? (validation.value as RankedListConfig) : {},
  );
  let ranked = $derived(
    rows ? projectRankedList(rows as Array<Record<string, unknown>>, config) : [],
  );
</script>

{#if !validation.ok}
  <div class="ranked__invalid">{validation.error}</div>
{:else if !rows}
  <div class="ranked__loading">Loading…</div>
{:else if ranked.length === 0}
  <div class="ranked__loading">No data</div>
{:else}
  <div class="ranked">
    {#if config.subtitle}
      <div class="ranked__subtitle">{config.subtitle}</div>
    {/if}
    <ol class="ranked__list">
      {#each ranked as row (row.rank + row.label)}
        <li class="ranked__row">
          {#if config.showRank !== false}
            <span class="ranked__rank" aria-hidden="true">{row.rank}</span>
          {/if}
          <span class="ranked__label">{row.label}</span>
          <span class="ranked__value" data-tone={row.tone}>{row.formatted}</span>
        </li>
      {/each}
    </ol>
  </div>
{/if}

<style>
  .ranked {
    height: 100%;
    overflow-y: auto;
    padding: 0 0.25rem 0.25rem;
    box-sizing: border-box;
  }
  .ranked__subtitle {
    font-size: 0.72rem;
    color: var(--saiku-app-muted, #77716a);
    padding: 0 0.75rem 0.4rem;
  }
  .ranked__list {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .ranked__row {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.5rem 0.75rem;
    border-top: 1px dashed var(--saiku-app-card-border, #e6e2d8);
  }
  .ranked__row:first-child {
    border-top: 0;
  }
  .ranked__rank {
    flex: none;
    width: 1.25rem;
    font-family: var(--saiku-app-font-display, inherit);
    font-style: italic;
    font-size: 0.95rem;
    color: var(--saiku-app-muted, #77716a);
  }
  .ranked__label {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 0.88rem;
    font-weight: 550;
    color: var(--saiku-app-fg, #23282e);
  }
  .ranked__value {
    flex: none;
    font-family: var(--saiku-app-font-numeric, var(--saiku-app-font-body, inherit));
    font-variant-numeric: tabular-nums;
    font-size: 0.82rem;
    font-weight: 650;
  }
  .ranked__value[data-tone="positive"] {
    color: var(--saiku-app-positive, #2e7d55);
  }
  .ranked__value[data-tone="negative"] {
    color: var(--saiku-app-danger, #c0492b);
  }
  .ranked__value[data-tone="flat"] {
    color: var(--saiku-app-muted, #77716a);
  }
  .ranked__invalid,
  .ranked__loading {
    padding: 0.75rem;
    font-size: 0.8rem;
    color: var(--saiku-app-muted, #77716a);
  }
</style>

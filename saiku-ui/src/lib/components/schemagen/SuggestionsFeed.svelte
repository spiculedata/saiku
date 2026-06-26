<script lang="ts">
  /*
   * Right-pane suggestions feed for the schema-generator wizard.
   *
   * Groups the incoming ops by variant (rename / hierarchy / aggregator /
   * degenerateDim / ignore), renders a header per group with a count and
   * "Accept all ≥ high" bulk action when any high-confidence ops exist, and
   * delegates per-card rendering to SuggestionCard.
   *
   * Accept / reject is surfaced up to the parent via the `onAccept` / `onReject`
   * callbacks — the feed is otherwise stateless apart from tracking locally
   * rejected paths so rejected cards disappear without requiring a round trip.
   */
  import {
    filterHighConfidence,
    groupOps,
    type FeedSuggestionOp,
    type FeedSuggestionView,
  } from "./suggestionsFeed.model";
  import SuggestionCard from "./SuggestionCard.svelte";

  interface Props {
    suggestions: FeedSuggestionView | null;
    onAccept: (op: FeedSuggestionOp) => void;
    onReject: (op: FeedSuggestionOp) => void;
  }

  let { suggestions, onAccept, onReject }: Props = $props();

  // Locally-rejected op keys so the card vanishes immediately; the parent is
  // still notified so it can remove the op from its store if it wants.
  let rejectedKeys = $state(new Set<string>());

  function opKey(op: FeedSuggestionOp): string {
    // Discriminator + path is unique per op within a run.
    return `${op.op}:${op.targetPath}`;
  }

  const visibleOps = $derived(
    (suggestions?.ops ?? []).filter((o) => !rejectedKeys.has(opKey(o))),
  );

  const groups = $derived(groupOps(visibleOps));

  function handleAccept(op: FeedSuggestionOp) {
    onAccept(op);
  }

  function handleReject(op: FeedSuggestionOp) {
    // Immutable update so Svelte picks up the state change.
    const next = new Set(rejectedKeys);
    next.add(opKey(op));
    rejectedKeys = next;
    onReject(op);
  }

  function acceptAllHigh(ops: FeedSuggestionOp[]) {
    for (const op of filterHighConfidence(ops)) onAccept(op);
  }

  function highCount(ops: FeedSuggestionOp[]): number {
    return filterHighConfidence(ops).length;
  }
</script>

<section class="feed" aria-label="Enrichment suggestions">
  {#if suggestions === null}
    <p class="text-fg-muted">No suggestions loaded.</p>
  {:else}
    {#if suggestions.degraded}
      <div class="feed__banner" role="status">
        Enrichment degraded — some suggestions may be missing or lower quality.
      </div>
    {/if}

    {#if groups.length === 0}
      <p class="text-fg-muted">No suggestions to review.</p>
    {:else}
      {#each groups as group (group.type)}
        <section class="feed__group" data-group={group.type}>
          <header class="flex items-center justify-between gap-2 pb-1 border-b border-border">
            <h3 class="m-0 text-sm font-semibold inline-flex items-baseline gap-2">
              {group.title}
              <span class="text-xs font-normal text-fg-muted">{group.ops.length}</span>
            </h3>
            {#if highCount(group.ops) > 0}
              <button
                type="button"
                class="feed__bulk"
                onclick={() => acceptAllHigh(group.ops)}
                aria-label="Accept all high-confidence {group.title}"
              >
                Accept all ≥ high ({highCount(group.ops)})
              </button>
            {/if}
          </header>

          <ul class="list-none m-0 p-0 flex flex-col gap-2">
            {#each group.ops as op (opKey(op))}
              <li>
                <SuggestionCard
                  {op}
                  onAccept={() => handleAccept(op)}
                  onReject={() => handleReject(op)}
                />
              </li>
            {/each}
          </ul>
        </section>
      {/each}
    {/if}
  {/if}
</section>

<style>
.feed {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    padding: var(--space-3);
    font-family: var(--font-sans);
    font-size: var(--fs-sm);
    color: var(--fg);
    overflow: auto;
  }
  .feed__banner {
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--warning, var(--border-strong));
    background: var(--bg-muted);
    color: var(--fg);
    border-radius: var(--radius-sm);
    font-size: var(--fs-xs);
  }
  .feed__group {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  .feed__bulk {
    font: inherit;
    font-size: var(--fs-xs);
    padding: 3px var(--space-2);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg);
    color: var(--fg);
    cursor: pointer;
  }
  .feed__bulk:hover {
    background: var(--bg-muted);
  }
  .feed__bulk:focus-visible {
    outline: none;
    box-shadow: var(--focus-ring);
  }
</style>

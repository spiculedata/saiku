<script lang="ts">
  /*
   * A single suggestion card — target path, before → after preview, a
   * confidence-tier pill, the rationale, and Accept / Reject buttons.
   *
   * Purely presentational; all derivation (tier bucketing, the before / after
   * strings) lives in suggestionsFeed.model.ts so it can be unit-tested.
   */
  import { CheckCircle, XCircle } from "lucide-svelte";

  import {
    confidenceTier,
    describeOp,
    type FeedSuggestionOp,
  } from "./suggestionsFeed.model";

  interface Props {
    op: FeedSuggestionOp;
    onAccept: () => void;
    onReject: () => void;
  }

  let { op, onAccept, onReject }: Props = $props();

  const tier = $derived(confidenceTier(op));
  const described = $derived(describeOp(op));
</script>

<article class="card" data-op={op.op} data-tier={tier}>
  <header class="card__head">
    <code class="card__path">{op.targetPath}</code>
    <span
      class="card__tier card__tier--{tier}"
      title="Confidence {(op.confidence * 100).toFixed(0)}%"
    >
      {tier}
    </span>
  </header>

  <div class="card__preview">
    <span class="card__before">{described.before}</span>
    <span class="card__arrow" aria-hidden="true">→</span>
    <span class="card__after">{described.after}</span>
  </div>

  <p class="card__rationale">{described.rationale}</p>

  <footer class="card__actions">
    <button
      type="button"
      class="card__btn card__btn--accept"
      onclick={onAccept}
      aria-label="Accept suggestion"
    >
      <CheckCircle size={14} />
      <span>Accept</span>
    </button>
    <button
      type="button"
      class="card__btn card__btn--reject"
      onclick={onReject}
      aria-label="Reject suggestion"
    >
      <XCircle size={14} />
      <span>Reject</span>
    </button>
  </footer>
</article>

<style>
  .card {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg);
    font-size: var(--fs-sm);
  }
  .card__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
  }
  .card__path {
    font-family: var(--font-mono);
    font-size: var(--fs-xs);
    color: var(--fg-muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .card__tier {
    font-size: 10px;
    line-height: 1;
    padding: 3px 8px;
    border-radius: 999px;
    font-weight: 600;
    letter-spacing: 0.03em;
    text-transform: uppercase;
    color: var(--accent-fg);
    background: var(--fg-subtle);
  }
  .card__tier--high {
    background: var(--success);
    color: #fff;
  }
  .card__tier--medium {
    background: var(--accent);
    color: var(--accent-fg);
  }
  .card__tier--low {
    background: var(--border-strong);
    color: var(--fg);
  }
  .card__preview {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    flex-wrap: wrap;
  }
  .card__before {
    color: var(--fg-muted);
    text-decoration: line-through;
  }
  .card__arrow {
    color: var(--fg-muted);
  }
  .card__after {
    font-weight: 600;
  }
  .card__rationale {
    margin: 0;
    color: var(--fg-muted);
    font-size: var(--fs-xs);
  }
  .card__actions {
    display: flex;
    gap: var(--space-2);
    justify-content: flex-end;
  }
  .card__btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font: inherit;
    font-size: var(--fs-xs);
    padding: 4px var(--space-3);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg);
    color: var(--fg);
    cursor: pointer;
  }
  .card__btn:hover {
    background: var(--bg-muted);
  }
  .card__btn:focus-visible {
    outline: none;
    box-shadow: var(--focus-ring);
  }
  .card__btn--accept {
    color: var(--success);
    border-color: var(--success);
  }
  .card__btn--accept:hover {
    background: var(--success);
    color: #fff;
  }
  .card__btn--reject {
    color: var(--fg-muted);
  }
</style>

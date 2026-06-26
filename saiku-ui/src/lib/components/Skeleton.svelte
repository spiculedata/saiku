<script lang="ts">
  /*
   * Skeleton placeholder for async list/table content.
   *
   * Prefer over a plain "Loading…" — the shimmer rows communicate
   * shape (what's about to render) and progress (something is
   * happening) at the same time. Use `rows` to roughly match the
   * eventual content density.
   *
   * Respects prefers-reduced-motion: the shimmer animation is
   * suppressed and the bars render at a flat resting opacity.
   */

  interface Props {
    rows?: number;
    /** Variant tuning the bar layout for the surface it sits in. */
    variant?: "list" | "table";
  }

  let { rows = 5, variant = "list" }: Props = $props();
</script>

<div class="skeleton skeleton--{variant}" aria-busy="true" aria-live="polite">
  {#each Array(rows) as _, i (i)}
    <div class="skeleton__row">
      {#if variant === "list"}
        <div class="skeleton__bar skeleton__bar--title"></div>
        <div class="skeleton__bar skeleton__bar--meta"></div>
      {:else}
        <div class="skeleton__bar skeleton__bar--cell"></div>
        <div class="skeleton__bar skeleton__bar--cell"></div>
        <div class="skeleton__bar skeleton__bar--cell"></div>
      {/if}
    </div>
  {/each}
</div>

<style>
  .skeleton {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3) 0;
  }

  .skeleton--list .skeleton__row {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
  }
  .skeleton--list .skeleton__row:last-child { border-bottom: 0; }

  .skeleton--table .skeleton__row {
    display: grid;
    grid-template-columns: 2fr 1fr 1fr;
    gap: var(--space-3);
    padding: var(--space-3);
    border-bottom: 1px solid var(--border);
  }

  .skeleton__bar {
    height: 10px;
    border-radius: var(--radius-sm);
    background: linear-gradient(
      90deg,
      var(--bg-muted) 0%,
      var(--bg-subtle) 50%,
      var(--bg-muted) 100%
    );
    background-size: 200% 100%;
    animation: skeleton-shimmer 1.4s ease-in-out infinite;
  }

  .skeleton__bar--title { width: 60%; height: 12px; }
  .skeleton__bar--meta { width: 35%; height: 9px; }
  .skeleton__bar--cell { width: 80%; }

  @keyframes skeleton-shimmer {
    0% { background-position: 200% 0; }
    100% { background-position: -200% 0; }
  }

  @media (prefers-reduced-motion: reduce) {
    .skeleton__bar {
      animation: none;
      background: var(--bg-subtle);
    }
  }
</style>

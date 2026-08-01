<script lang="ts">
  /*
   * Shared "simple ↔ MDX" tab strip — extracted from OrderModal /
   * TopBottomCountModal / LimitModal (saiku#1232). Each axis-filter
   * modal that surfaces an "MDX escape hatch" alongside a structured
   * picker rendered its own copy of this row + ~30 lines of identical
   * CSS. One source of truth here.
   *
   * Parent owns the {@code mode} state; this component is a controlled
   * tab list — onChange is called when the user clicks the other tab,
   * never on first paint. Switching tabs is the parent's chance to seed
   * the MDX buffer from the simple selection (or vice-versa).
   */
  import { i18n } from "$lib/stores/i18n.svelte";

  type Mode = "simple" | "mdx";

  interface Props {
    mode: Mode;
    onChange: (next: Mode) => void;
  }

  let { mode, onChange }: Props = $props();

  function pick(next: Mode): void {
    if (next !== mode) onChange(next);
  }
</script>

<div
  class="mode-switch"
  role="tablist"
  aria-label={i18n.t("modal.filter.mode")}
>
  <button
    type="button"
    role="tab"
    class="mode-switch__btn"
    class:active={mode === "simple"}
    aria-selected={mode === "simple"}
    onclick={() => pick("simple")}
  >
    {i18n.t("modal.filter.modeSimple")}
  </button>
  <button
    type="button"
    role="tab"
    class="mode-switch__btn"
    class:active={mode === "mdx"}
    aria-selected={mode === "mdx"}
    onclick={() => pick("mdx")}
  >
    {i18n.t("modal.filter.modeMdx")}
  </button>
</div>

<style>
  .mode-switch {
    display: inline-flex;
    margin-bottom: var(--space-3);
    background: hsl(var(--bg-muted));
    border: 1px solid hsl(var(--border));
    border-radius: var(--radius-sm);
    padding: 2px;
  }
  .mode-switch__btn {
    padding: 4px var(--space-3);
    background: transparent;
    border: 0;
    border-radius: 3px;
    color: hsl(var(--fg-muted));
    font: inherit;
    font-size: var(--fs-sm);
    cursor: pointer;
  }
  .mode-switch__btn:hover {
    color: hsl(var(--fg));
  }
  .mode-switch__btn.active {
    background: hsl(var(--bg));
    color: hsl(var(--fg));
    font-weight: var(--weight-medium);
    box-shadow: var(--shadow-sm);
  }
</style>

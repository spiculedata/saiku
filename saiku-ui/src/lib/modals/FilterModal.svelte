<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/FilterModal.js — custom MDX
   * expression (ORDER / FILTER / LIMIT / TOPCOUNT) applied to an axis. */
  type FilterExpressionType =
    | "Order"
    | "Filter"
    | "TopCount"
    | "BottomCount"
    | "Limit";
  const SORT_FUNCTIONS = ["ASC", "BASC", "DESC", "BDESC"] as const;

  interface Props {
    axis: string;
    expressionType: FilterExpressionType;
    expression: string;
    sortFunction?: (typeof SORT_FUNCTIONS)[number];
    open: boolean;
    onSave: (expression: string, sort?: string) => void;
    onCancel: () => void;
  }

  let {
    axis,
    expressionType,
    expression,
    sortFunction = "ASC",
    open,
    onSave,
    onCancel,
  }: Props = $props();

  let buffer = $state<string>(expression);
  let sort = $state<string>(sortFunction);

  $effect(() => {
    if (open) {
      buffer = expression;
      sort = sortFunction;
    }
  });
</script>

<Modal title={`Custom ${expressionType} for ${axis}`} {open} size="lg" onClose={onCancel}>
  {#if expressionType === "Order"}
    <label class="field">
      <span class="field__label">Sort</span>
      <select class="field__input" bind:value={sort}>
        {#each SORT_FUNCTIONS as fn}
          <option value={fn}>{fn}</option>
        {/each}
      </select>
    </label>
  {/if}
  <label class="field">
    <span class="field__label">{expressionType} MDX expression</span>
    <textarea class="field__input mdx" bind:value={buffer} rows="8" spellcheck="false"></textarea>
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button type="button" class="btn btn--primary" onclick={() => onSave(buffer, sort)}>OK</button>
  {/snippet}
</Modal>

<style>
  .mdx { font-family: var(--font-mono); font-size: var(--fs-sm); resize: vertical; }
</style>

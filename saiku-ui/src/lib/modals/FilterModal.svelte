<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import MonacoEditor from "$lib/components/MonacoEditor.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

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

<Modal title={`${i18n.t("modal.filter.custom")} ${expressionType} ${i18n.t("modal.filter.for")} ${axis}`} {open} size="lg" onClose={onCancel}>
  {#if expressionType === "Order"}
    <label class="field">
      <span class="field__label">{i18n.t("modal.filter.sort")}</span>
      <select class="field__input" bind:value={sort}>
        {#each SORT_FUNCTIONS as fn}
          <option value={fn}>{fn}</option>
        {/each}
      </select>
    </label>
  {/if}
  <div class="field">
    <span class="field__label">{expressionType} {i18n.t("modal.filter.mdxExpression")}</span>
    {#if open}
      <MonacoEditor value={buffer} language="mdx" minHeight="220px" onChange={(v) => (buffer = v)} />
    {/if}
  </div>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button type="button" class="btn btn--primary" onclick={() => onSave(buffer, sort)}>{i18n.t("modal.ok")}</button>
  {/snippet}
</Modal>

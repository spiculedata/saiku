<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import ModalActions from "$lib/modals/parts/ModalActions.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/CustomFilterModal.js.
   * Quick one-shot filter on a measure: operator + value. */
  type FilterOperator =
    | ">"
    | ">="
    | "<"
    | "<="
    | "="
    | "!="
    | "BETWEEN"
    | "NOT BETWEEN";

  const OPERATORS: FilterOperator[] = [
    ">", ">=", "<", "<=", "=", "!=", "BETWEEN", "NOT BETWEEN",
  ];

  interface Props {
    measureCaption: string;
    open: boolean;
    onApply: (op: FilterOperator, value: string, value2?: string) => void;
    onCancel: () => void;
  }

  let { measureCaption, open, onApply, onCancel }: Props = $props();
  let op = $state<FilterOperator>(">");
  let value = $state<string>("");
  let value2 = $state<string>("");

  $effect(() => {
    if (open) {
      op = ">";
      value = "";
      value2 = "";
    }
  });

  const needsSecond = $derived(op === "BETWEEN" || op === "NOT BETWEEN");
</script>

<Modal title={`${i18n.t("modal.customFilter.title")} ${measureCaption}`} {open} size="md" onClose={onCancel}>
  <div class="row">
    <label class="field field--grow">
      <span class="field__label">{i18n.t("modal.customFilter.operator")}</span>
      <select class="field__input" bind:value={op}>
        {#each OPERATORS as o}
          <option value={o}>{o}</option>
        {/each}
      </select>
    </label>
    <label class="field field--grow">
      <span class="field__label">{i18n.t("modal.customFilter.value")}</span>
      <input class="field__input" type="number" bind:value />
    </label>
    {#if needsSecond}
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.customFilter.and")}</span>
        <input class="field__input" type="number" bind:value={value2} />
      </label>
    {/if}
  </div>
  {#snippet footer()}
    <ModalActions
      {onCancel}
      onApply={() => onApply(op, value, needsSecond ? value2 : undefined)}
      primaryKey="modal.apply"
    />
  {/snippet}
</Modal>

<style>
  .row { display: flex; gap: var(--space-3); }
  .field--grow { flex: 1; }
</style>

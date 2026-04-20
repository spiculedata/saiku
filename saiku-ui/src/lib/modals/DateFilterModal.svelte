<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/DateFilterModal.js. */
  type DateFilterType =
    | "YEAR_TO_DATE"
    | "MONTH_TO_DATE"
    | "QUARTER_TO_DATE"
    | "WEEK_TO_DATE"
    | "LAST_N_DAYS"
    | "LAST_N_MONTHS"
    | "CUSTOM_RANGE";

  const DATE_FILTER_TYPES: DateFilterType[] = [
    "YEAR_TO_DATE",
    "QUARTER_TO_DATE",
    "MONTH_TO_DATE",
    "WEEK_TO_DATE",
    "LAST_N_DAYS",
    "LAST_N_MONTHS",
    "CUSTOM_RANGE",
  ];

  export interface DateFilterValue {
    type: DateFilterType;
    n?: number;
    from?: string;
    to?: string;
  }

  interface Props {
    initial?: DateFilterValue;
    open: boolean;
    onApply: (v: DateFilterValue) => void;
    onCancel: () => void;
  }

  let {
    initial = { type: "YEAR_TO_DATE" },
    open,
    onApply,
    onCancel,
  }: Props = $props();

  let value = $state<DateFilterValue>({ ...initial });

  $effect(() => {
    if (open) value = { ...initial };
  });

  const isN = $derived(value.type === "LAST_N_DAYS" || value.type === "LAST_N_MONTHS");
  const isRange = $derived(value.type === "CUSTOM_RANGE");
</script>

<Modal title={i18n.t("modal.dateFilter.title")} {open} size="md" onClose={onCancel}>
  <label class="field">
    <span class="field__label">{i18n.t("modal.dateFilter.type")}</span>
    <select class="field__input" bind:value={value.type}>
      {#each DATE_FILTER_TYPES as t}
        <option value={t}>{t.replaceAll("_", " ")}</option>
      {/each}
    </select>
  </label>
  {#if isN}
    <label class="field">
      <span class="field__label">N</span>
      <input class="field__input" type="number" min="1" bind:value={value.n} />
    </label>
  {:else if isRange}
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.dateFilter.from")}</span>
        <input class="field__input" type="date" bind:value={value.from} />
      </label>
      <label class="field field--grow">
        <span class="field__label">{i18n.t("modal.dateFilter.to")}</span>
        <input class="field__input" type="date" bind:value={value.to} />
      </label>
    </div>
  {/if}
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button type="button" class="btn btn--primary" onclick={() => onApply(value)}>{i18n.t("modal.apply")}</button>
  {/snippet}
</Modal>

<style>
  .row { display: flex; gap: var(--space-3); }
  .field--grow { flex: 1; }
</style>

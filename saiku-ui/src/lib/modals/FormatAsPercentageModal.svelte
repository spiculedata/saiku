<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/FormatAsPercentageModal.js. */
  export type PercentageAxis = "COLUMNS" | "ROWS" | "GRAND_TOTAL";
  export type PercentageScope = "all" | "selected";

  interface Props {
    defaultAxis: PercentageAxis;
    scope: PercentageScope;
    open: boolean;
    onApply: (axis: PercentageAxis, scope: PercentageScope) => void;
    onCancel: () => void;
  }

  let { defaultAxis, scope, open, onApply, onCancel }: Props = $props();
  let axis = $state<PercentageAxis>(untrack(() => defaultAxis));
  let s = $state<PercentageScope>(untrack(() => scope));

  $effect(() => {
    if (open) {
      axis = defaultAxis;
      s = scope;
    }
  });
</script>

<Modal title={i18n.t("modal.percent.title")} {open} size="md" onClose={onCancel}>
  <label class="field">
    <span class="field__label">{i18n.t("modal.percent.baseAxis")}</span>
    <select class="field__input" bind:value={axis}>
      <option value="ROWS">{i18n.t("modal.percent.rowTotal")}</option>
      <option value="COLUMNS">{i18n.t("modal.percent.columnTotal")}</option>
      <option value="GRAND_TOTAL">{i18n.t("modal.percent.grandTotal")}</option>
    </select>
  </label>
  <label class="field">
    <span class="field__label">{i18n.t("modal.percent.applyTo")}</span>
    <select class="field__input" bind:value={s}>
      <option value="all">{i18n.t("modal.percent.allMeasures")}</option>
      <option value="selected">{i18n.t("modal.percent.selectedOnly")}</option>
    </select>
  </label>
  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button >onApply(axis, s)}>{i18n.t("modal.apply")}</Button>
  {/snippet}
</Modal>

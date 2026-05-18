<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type { SaikuMeasure } from "$lib/api/discover";

  /*
   * Structured "Order axis by measure" picker.
   *
   * Replaces the generic Monaco MDX editor for the Order axis-filter
   * type. Users pick a sort direction and a measure from the cube's
   * measure list; we hand the unique name back to QueryCanvas which
   * wires it into queryModel.axes[axis].sortEvaluationLiteral. No MDX
   * authoring required.
   */
  const SORT_FUNCTIONS = ["ASC", "BASC", "DESC", "BDESC"] as const;
  type SortFn = (typeof SORT_FUNCTIONS)[number];

  interface Props {
    axis: string;
    measures: SaikuMeasure[];
    initialMeasure: string;
    initialSort: SortFn;
    open: boolean;
    onSave: (measureUniqueName: string, sort: SortFn) => void;
    onCancel: () => void;
  }

  let {
    axis,
    measures,
    initialMeasure,
    initialSort,
    open,
    onSave,
    onCancel,
  }: Props = $props();

  let selected = $state<string>(initialMeasure);
  let sort = $state<SortFn>(initialSort);

  $effect(() => {
    if (open) {
      selected = initialMeasure || measures[0]?.uniqueName || "";
      sort = initialSort;
    }
  });

  function commit() {
    if (!selected) return;
    onSave(selected, sort);
  }
</script>

<Modal
  title={`${i18n.t("modal.filter.order")} ${axis}`}
  {open}
  size="md"
  onClose={onCancel}
>
  <label class="field">
    <span class="field__label">{i18n.t("modal.filter.sort")}</span>
    <select class="field__input" bind:value={sort}>
      {#each SORT_FUNCTIONS as fn}
        <option value={fn}>{fn}</option>
      {/each}
    </select>
  </label>
  <label class="field">
    <span class="field__label">{i18n.t("modal.filter.byMeasure")}</span>
    <select class="field__input" bind:value={selected}>
      {#if !initialMeasure && measures.length > 0}
        <option value="" disabled>{i18n.t("modal.filter.pickMeasure")}</option>
      {/if}
      {#each measures as m}
        <option value={m.uniqueName}>{m.caption || m.name}</option>
      {/each}
    </select>
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button
      type="button"
      class="btn btn--primary"
      disabled={!selected}
      onclick={commit}
    >{i18n.t("modal.ok")}</button>
  {/snippet}
</Modal>

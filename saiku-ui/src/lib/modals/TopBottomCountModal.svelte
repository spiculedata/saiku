<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type { SaikuMeasure } from "$lib/api/discover";

  /*
   * Structured "Top N / Bottom N by measure" picker.
   *
   * Replaces the generic Monaco MDX editor for TopCount / BottomCount
   * axis-filter types. The user picks N and a measure; we hand back
   * "N, [Measure].[uniqueName]" — the exact string the legacy generic
   * modal required the user to type. QueryCanvas wraps that into
   * TOPCOUNT(...) or BOTTOMCOUNT(...) MDX.
   */
  type Variant = "top" | "bottom";

  interface Props {
    axis: string;
    variant: Variant;
    measures: SaikuMeasure[];
    initialCount: number;
    initialMeasure: string;
    open: boolean;
    onSave: (expression: string) => void;
    onCancel: () => void;
  }

  let {
    axis,
    variant,
    measures,
    initialCount,
    initialMeasure,
    open,
    onSave,
    onCancel,
  }: Props = $props();

  let count = $state<number>(initialCount);
  let selected = $state<string>(initialMeasure);

  $effect(() => {
    if (open) {
      count = initialCount || 10;
      selected = initialMeasure || measures[0]?.uniqueName || "";
    }
  });

  function commit() {
    if (!selected || !Number.isFinite(count) || count < 1) return;
    onSave(`${Math.floor(count)}, ${selected}`);
  }

  const titleKey = $derived(variant === "top" ? "modal.filter.topCount" : "modal.filter.bottomCount");
</script>

<Modal
  title={`${i18n.t(titleKey)} ${axis}`}
  {open}
  size="md"
  onClose={onCancel}
>
  <label class="field">
    <span class="field__label">{i18n.t("modal.filter.count")}</span>
    <input
      class="field__input"
      type="number"
      min="1"
      step="1"
      bind:value={count}
    />
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
      disabled={!selected || !(count >= 1)}
      onclick={commit}
    >{i18n.t("modal.ok")}</button>
  {/snippet}
</Modal>

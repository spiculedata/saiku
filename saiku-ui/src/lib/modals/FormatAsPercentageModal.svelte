<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

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
  let axis = $state<PercentageAxis>(defaultAxis);
  let s = $state<PercentageScope>(scope);

  $effect(() => {
    if (open) {
      axis = defaultAxis;
      s = scope;
    }
  });
</script>

<Modal title="Format as percentage" {open} size="md" onClose={onCancel}>
  <label class="field">
    <span class="field__label">Base axis</span>
    <select class="field__input" bind:value={axis}>
      <option value="ROWS">Row total</option>
      <option value="COLUMNS">Column total</option>
      <option value="GRAND_TOTAL">Grand total</option>
    </select>
  </label>
  <label class="field">
    <span class="field__label">Apply to</span>
    <select class="field__input" bind:value={s}>
      <option value="all">All measures</option>
      <option value="selected">Selected cells only</option>
    </select>
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button type="button" class="btn btn--primary" onclick={() => onApply(axis, s)}>Apply</button>
  {/snippet}
</Modal>

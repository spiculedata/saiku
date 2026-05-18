<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /*
   * Structured "Limit axis to first N members" picker.
   *
   * Replaces the generic Monaco MDX editor for the Limit axis-filter
   * type. User picks N; QueryCanvas wraps the count in HEAD(base, N).
   */
  interface Props {
    axis: string;
    initialCount: number;
    open: boolean;
    onSave: (count: string) => void;
    onCancel: () => void;
  }

  let { axis, initialCount, open, onSave, onCancel }: Props = $props();

  let count = $state<number>(initialCount);

  $effect(() => {
    if (open) count = initialCount || 10;
  });

  function commit() {
    if (!Number.isFinite(count) || count < 1) return;
    onSave(String(Math.floor(count)));
  }
</script>

<Modal
  title={`${i18n.t("modal.filter.limit")} ${axis}`}
  {open}
  size="sm"
  onClose={onCancel}
>
  <label class="field">
    <span class="field__label">{i18n.t("modal.filter.limitCount")}</span>
    <input
      class="field__input"
      type="number"
      min="1"
      step="1"
      bind:value={count}
    />
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button
      type="button"
      class="btn btn--primary"
      disabled={!(count >= 1)}
      onclick={commit}
    >{i18n.t("modal.ok")}</button>
  {/snippet}
</Modal>

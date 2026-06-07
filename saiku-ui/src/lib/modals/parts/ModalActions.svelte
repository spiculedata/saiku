<script lang="ts">
  /*
   * Shared Cancel / primary-action footer — extracted from the axis-
   * filter modal family (saiku#1232). Drop into a Modal's
   * {@code {#snippet footer()}} slot to avoid hand-rolling the same
   * pair of buttons in every modal.
   *
   * Caller picks the primary label via i18n key (defaults to "modal.ok",
   * but Apply / Save / Apply-and-close variants are common).
   */
  import { i18n } from "$lib/stores/i18n.svelte";

  interface Props {
    onCancel: () => void;
    onApply: () => void;
    /** i18n key for the primary button; defaults to "modal.ok". */
    primaryKey?: string;
    /** When false, the primary button is disabled. */
    enabled?: boolean;
  }

  let {
    onCancel,
    onApply,
    primaryKey = "modal.ok",
    enabled = true,
  }: Props = $props();
</script>

<button type="button" class="btn" onclick={onCancel}>
  {i18n.t("modal.cancel")}
</button>
<button
  type="button"
  class="btn btn--primary"
  disabled={!enabled}
  onclick={onApply}
>
  {i18n.t(primaryKey)}
</button>

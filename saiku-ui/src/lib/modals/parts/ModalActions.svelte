<script lang="ts">
  /*
   * Shared Cancel / primary-action footer — extracted from the axis-
   * filter modal family (saiku#1232). Drop into a Modal's
   * {@code {#snippet footer()}} slot to avoid hand-rolling the same
   * pair of buttons in every modal.
   *
   * Caller picks the primary label via i18n key (defaults to "modal.ok",
   * but Apply / Save / Apply-and-close variants are common).
   *
   * Migrated to the design-system Button primitive so every modal
   * using ModalActions automatically inherits the typed variant /
   * size / disabled treatment without per-modal style drift.
   */
  import { i18n } from "$lib/stores/i18n.svelte";
  import { Button } from "$lib/components/ui";

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

<Button variant="outline" onclick={onCancel}>
  {i18n.t("modal.cancel")}
</Button>
<Button disabled={!enabled} onclick={onApply}>
  {i18n.t(primaryKey)}
</Button>

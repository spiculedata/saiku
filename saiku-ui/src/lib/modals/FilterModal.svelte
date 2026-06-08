<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import MonacoEditor from "$lib/components/MonacoEditor.svelte";
  import ModalActions from "$lib/modals/parts/ModalActions.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /*
   * Arbitrary MDX FILTER expression editor.
   *
   * Specialized to the Filter axis-filter type — Order / TopCount /
   * BottomCount / Limit each have their own structured modal that
   * collects the parameters and emits the right string. Filter is the
   * only type where the user genuinely needs to author boolean MDX
   * (e.g. `[Measures].[Unit Sales] > 100000`), so this modal keeps
   * the Monaco editor.
   */
  interface Props {
    axis: string;
    expression: string;
    open: boolean;
    onSave: (expression: string) => void;
    onCancel: () => void;
  }

  let { axis, expression, open, onSave, onCancel }: Props = $props();

  let buffer = $state<string>(untrack(() => expression));

  $effect(() => {
    if (open) buffer = expression;
  });
</script>

<Modal
  title={`${i18n.t("modal.filter.custom")} Filter ${i18n.t("modal.filter.for")} ${axis}`}
  {open}
  size="lg"
  onClose={onCancel}
>
  <div class="field">
    <span class="field__label">Filter {i18n.t("modal.filter.mdxExpression")}</span>
    {#if open}
      <MonacoEditor value={buffer} language="mdx" minHeight="220px" onChange={(v) => (buffer = v)} />
    {/if}
  </div>
  {#snippet footer()}
    <ModalActions {onCancel} onApply={() => onSave(buffer)} />
  {/snippet}
</Modal>

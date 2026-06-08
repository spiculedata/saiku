<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import MonacoEditor from "$lib/components/MonacoEditor.svelte";
  import ModalModeSwitch from "$lib/modals/parts/ModalModeSwitch.svelte";
  import ModalActions from "$lib/modals/parts/ModalActions.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /*
   * "Limit axis to first N members" picker — two modes.
   *
   * - "simple": just a number input.
   * - "mdx":    Monaco editor for a numeric MDX expression (rare but
   *   supported — power users may want a calculated count). QueryCanvas
   *   wraps either result in HEAD(base, <expr>).
   */
  type Mode = "simple" | "mdx";

  interface Props {
    axis: string;
    initialCount: number;
    open: boolean;
    onSave: (expression: string) => void;
    onCancel: () => void;
  }

  let { axis, initialCount, open, onSave, onCancel }: Props = $props();

  let mode = $state<Mode>("simple");
  let count = $state<number>(untrack(() => initialCount));
  let mdxBuffer = $state<string>("");

  $effect(() => {
    if (open) {
      mode = "simple";
      count = initialCount || 10;
      mdxBuffer = String(initialCount || 10);
    }
  });

  function switchMode(next: Mode) {
    if (next === mode) return;
    if (next === "mdx") mdxBuffer = String(Math.floor(count));
    mode = next;
  }

  function commit() {
    if (mode === "simple") {
      if (!Number.isFinite(count) || count < 1) return;
      onSave(String(Math.floor(count)));
    } else {
      const expr = mdxBuffer.trim();
      if (!expr) return;
      onSave(expr);
    }
  }

  const valid = $derived(
    mode === "simple" ? count >= 1 : mdxBuffer.trim().length > 0,
  );
</script>

<Modal
  title={`${i18n.t("modal.filter.limit")} ${axis}`}
  {open}
  size={mode === "mdx" ? "lg" : "sm"}
  onClose={onCancel}
>
  <ModalModeSwitch {mode} onChange={switchMode} />

  {#if mode === "simple"}
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
  {:else}
    <div class="field">
      <span class="field__label">Limit {i18n.t("modal.filter.mdxExpression")}</span>
      {#if open}
        <MonacoEditor value={mdxBuffer} language="mdx" minHeight="160px" onChange={(v) => (mdxBuffer = v)} />
      {/if}
    </div>
  {/if}

  {#snippet footer()}
    <ModalActions {onCancel} onApply={commit} enabled={valid} />
  {/snippet}
</Modal>

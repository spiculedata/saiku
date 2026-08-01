<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import MonacoEditor from "$lib/components/MonacoEditor.svelte";
  import ModalModeSwitch from "$lib/modals/parts/ModalModeSwitch.svelte";
  import ModalActions from "$lib/modals/parts/ModalActions.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type { SaikuMeasure } from "$lib/api/discover";

  /*
   * "Top N / Bottom N" picker — two modes.
   *
   * - "simple": count + measure dropdown. Emits "N, [Measure]" string.
   * - "mdx":    Monaco editor for the full "N, <expression>" pair so
   *   power users can use calculated members, tuples, or expressions
   *   the simple form can't author. Same downstream MDX wrapping
   *   (TOPCOUNT / BOTTOMCOUNT) in QueryCanvas.
   */
  type Variant = "top" | "bottom";
  type Mode = "simple" | "mdx";

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

  let mode = $state<Mode>("simple");
  let count = $state<number>(untrack(() => initialCount));
  let selected = $state<string>(untrack(() => initialMeasure));
  let mdxBuffer = $state<string>("");

  $effect(() => {
    if (open) {
      // Don't read `count` / `selected` here — they were just written, and
      // reading state inside the same effect that wrote it turns the user's
      // keystrokes into a dep that re-fires this effect and resets the
      // value back to the prop. Use the source values instead.
      const seedCount = initialCount || 10;
      const seedMeasure = initialMeasure || measures[0]?.uniqueName || "";
      mode = "simple";
      count = seedCount;
      selected = seedMeasure;
      mdxBuffer = `${Math.floor(seedCount)}, ${seedMeasure}`;
    }
  });

  function switchMode(next: Mode) {
    if (next === mode) return;
    if (next === "mdx") mdxBuffer = `${Math.floor(count)}, ${selected}`;
    mode = next;
  }

  function commit() {
    if (mode === "simple") {
      if (!selected || !Number.isFinite(count) || count < 1) return;
      onSave(`${Math.floor(count)}, ${selected}`);
    } else {
      const expr = mdxBuffer.trim();
      if (!expr) return;
      onSave(expr);
    }
  }

  const valid = $derived(
    mode === "simple"
      ? !!selected && count >= 1
      : mdxBuffer.trim().length > 0,
  );

  const titleKey = $derived(variant === "top" ? "modal.filter.topCount" : "modal.filter.bottomCount");
</script>

<Modal
  title={`${i18n.t(titleKey)} ${axis}`}
  {open}
  size={mode === "mdx" ? "lg" : "md"}
  onClose={onCancel}
>
  <ModalModeSwitch {mode} onChange={switchMode} />

  {#if mode === "simple"}
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
  {:else}
    <div class="field">
      <span class="field__label">{i18n.t(titleKey)} {i18n.t("modal.filter.mdxExpression")}</span>
      {#if open}
        <MonacoEditor value={mdxBuffer} language="mdx" minHeight="200px" onChange={(v) => (mdxBuffer = v)} />
      {/if}
      <p class="field__hint">N, &lt;measure-or-expression&gt;</p>
    </div>
  {/if}

  {#snippet footer()}
    <ModalActions {onCancel} onApply={commit} enabled={valid} />
  {/snippet}
</Modal>

<style>
  .field__hint {
    margin: var(--space-1) 0 0;
    color: hsl(var(--fg-subtle));
    font-size: var(--fs-xs);
  }
</style>

<script lang="ts">
  import type { Snippet } from "svelte";

  interface Props {
    title: string;
    open: boolean;
    size?: "sm" | "md" | "lg" | "xl";
    onClose: () => void;
    children?: Snippet;
    footer?: Snippet;
  }

  let { title, open, size = "md", onClose, children, footer }: Props = $props();

  function onBackdropKey(e: KeyboardEvent) {
    if (e.key === "Escape") onClose();
  }
</script>

{#if open}
  <div
    class="modal"
    role="dialog"
    aria-modal="true"
    aria-label={title}
    tabindex="-1"
    onkeydown={onBackdropKey}
  >
    <button
      type="button"
      class="modal__backdrop"
      aria-label="Close dialog"
      onclick={onClose}
    ></button>
    <div class="modal__panel modal__panel--{size}">
      <header class="modal__header">
        <h2 class="modal__title">{title}</h2>
        <button type="button" class="modal__close" aria-label="Close" onclick={onClose}>×</button>
      </header>
      <div class="modal__body">
        {@render children?.()}
      </div>
      {#if footer}
        <footer class="modal__footer">
          {@render footer()}
        </footer>
      {/if}
    </div>
  </div>
{/if}

<style>
  .modal {
    position: fixed;
    inset: 0;
    display: grid;
    place-items: center;
    z-index: 1000;
  }
  .modal__backdrop {
    position: absolute;
    inset: 0;
    background: var(--bg-overlay);
    border: 0;
    cursor: pointer;
  }
  .modal__panel {
    position: relative;
    background: var(--bg);
    color: var(--fg);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-lg);
    max-height: calc(100vh - 48px);
    overflow: hidden;
    display: flex;
    flex-direction: column;
    min-width: 320px;
  }
  .modal__panel--sm { width: 360px; }
  .modal__panel--md { width: 520px; }
  .modal__panel--lg { width: 760px; }
  .modal__panel--xl { width: 960px; }
  .modal__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-4) var(--space-5);
    border-bottom: 1px solid var(--border);
  }
  .modal__title {
    margin: 0;
    font-size: var(--fs-lg);
    font-weight: 600;
  }
  .modal__close {
    background: transparent;
    border: 0;
    color: var(--fg-muted);
    font-size: 24px;
    line-height: 1;
    cursor: pointer;
    padding: 0 var(--space-1);
  }
  .modal__close:hover { color: var(--fg); }
  .modal__body {
    padding: var(--space-5);
    overflow: auto;
  }
  .modal__footer {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
    padding: var(--space-4) var(--space-5);
    border-top: 1px solid var(--border);
    background: var(--bg-muted);
  }
</style>

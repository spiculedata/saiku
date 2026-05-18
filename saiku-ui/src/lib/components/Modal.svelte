<script lang="ts">
  import type { Snippet } from "svelte";
  import { tick } from "svelte";
  import { X } from "lucide-svelte";

  interface Props {
    title: string;
    open: boolean;
    size?: "sm" | "md" | "lg" | "xl";
    onClose: () => void;
    children?: Snippet;
    footer?: Snippet;
  }

  let { title, open, size = "md", onClose, children, footer }: Props = $props();

  let panelEl = $state<HTMLElement | null>(null);
  let previouslyFocused: HTMLElement | null = null;
  // Unique id for aria-labelledby (module-level counter; ok in the browser).
  const titleId = `saiku-modal-title-${Math.random().toString(36).slice(2, 9)}`;

  function focusableIn(root: HTMLElement): HTMLElement[] {
    const nodes = root.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    return Array.from(nodes).filter(
      (el) => !el.hasAttribute("inert") && el.offsetParent !== null,
    );
  }

  $effect(() => {
    if (!open) return;
    previouslyFocused = (document.activeElement as HTMLElement | null) ?? null;
    // Wait a tick for the panel to mount, then focus the first focusable
    // that's NOT the "×" close button so users land on something useful.
    void tick().then(() => {
      if (!panelEl) return;
      const items = focusableIn(panelEl);
      const preferred = items.find((el) => !el.classList.contains("modal__close"));
      (preferred ?? items[0] ?? panelEl).focus();
    });
    return () => {
      // Restore focus to whatever was focused before the modal opened.
      // Use a rAF so the element is visible/focusable again after teardown.
      const prev = previouslyFocused;
      previouslyFocused = null;
      if (prev && typeof prev.focus === "function") {
        queueMicrotask(() => prev.focus());
      }
    };
  });

  function onKeyDown(e: KeyboardEvent) {
    if (!open) return;
    if (e.key === "Escape") {
      e.stopPropagation();
      onClose();
      return;
    }
    if (e.key !== "Tab" || !panelEl) return;
    const items = focusableIn(panelEl);
    if (items.length === 0) {
      e.preventDefault();
      panelEl.focus();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement as HTMLElement | null;
    if (e.shiftKey) {
      if (active === first || !panelEl.contains(active)) {
        e.preventDefault();
        last.focus();
      }
    } else {
      if (active === last) {
        e.preventDefault();
        first.focus();
      }
    }
  }
</script>

{#if open}
  <div
    class="modal"
    role="dialog"
    aria-modal="true"
    aria-labelledby={titleId}
    onkeydown={onKeyDown}
  >
    <button
      type="button"
      class="modal__backdrop"
      aria-label="Close dialog"
      tabindex="-1"
      onclick={onClose}
    ></button>
    <div
      class="modal__panel modal__panel--{size}"
      bind:this={panelEl}
      tabindex="-1"
    >
      <header class="modal__header">
        <h2 class="modal__title" id={titleId}>{title}</h2>
        <button type="button" class="icon-btn modal__close" aria-label="Close" onclick={onClose}>
          <X size={16} />
        </button>
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
    animation: saiku-overlay-in var(--duration-fast) ease;
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
    animation: saiku-panel-in var(--duration-normal) ease;
  }
  .modal__panel:focus { outline: none; }
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
    font-weight: var(--weight-semibold);
  }
  /* .modal__close inherits its shape from the global .icon-btn class;
     this rule remains as a placeholder for modal-specific tweaks. */
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

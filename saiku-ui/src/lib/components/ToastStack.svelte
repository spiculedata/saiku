<script lang="ts">
  import { toasts } from "$lib/stores/toasts.svelte";
  import { X } from "lucide-svelte";
</script>

<div class="toast-stack" role="status" aria-live="polite">
  {#each toasts.toasts as t (t.id)}
    <div
      class="toast toast--{t.variant}"
      role={t.variant === "danger" ? "alert" : "status"}
      aria-live={t.variant === "danger" ? "assertive" : "polite"}
    >
      <div class="toast__body">
        <strong class="toast__title">{t.title}</strong>
        {#if t.body}<span class="toast__text">{t.body}</span>{/if}
      </div>
      <button
        type="button"
        class="icon-btn toast__close"
        aria-label="Dismiss notification"
        onclick={() => toasts.dismiss(t.id)}
      >
        <X size={14} />
      </button>
    </div>
  {/each}
</div>

<style>
  .toast-stack {
    position: fixed;
    right: var(--space-4);
    bottom: var(--space-4);
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    z-index: 2000;
    pointer-events: none;
  }
  .toast {
    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
    min-width: 280px;
    max-width: 360px;
    background: var(--bg);
    color: var(--fg);
    border: 1px solid var(--border);
    border-left: 3px solid var(--accent);
    border-radius: var(--radius-md);
    box-shadow: var(--shadow-md);
    padding: var(--space-3);
    pointer-events: auto;
  }
  .toast--success { border-left-color: var(--success); }
  .toast--warning { border-left-color: var(--warning); }
  .toast--danger { border-left-color: var(--danger); }
  .toast__body {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 2px;
    font-size: var(--fs-sm);
  }
  .toast__title { font-size: var(--fs-md); }
  .toast__text { color: var(--fg-muted); }
  /* .toast__close inherits its shape from the global .icon-btn class. */
</style>

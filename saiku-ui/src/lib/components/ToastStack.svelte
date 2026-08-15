<script lang="ts">
  /*
   * Toast stack — pins to the bottom-right of the viewport and renders
   * every toast currently in $lib/stores/toasts. Each toast is a
   * design-system FeedbackBanner with a dismiss × button.
   *
   * The store keeps the lifecycle (deduping by burst; TTL; manual
   * dismiss); this component is purely the rendering layer.
   *
   * Tone mapping:
   *   store variant   → FeedbackBanner tone
   *   ---------------------------------------
   *   info            → info
   *   success         → success
   *   warning         → warning
   *   danger          → error
   *
   * The store's existing `variant` strings are kept (no churn in 30+
   * callers); the mapping happens here at the render boundary.
   */
  import { toasts } from "$lib/stores/toasts.svelte";
  import { FeedbackBanner, type Tone } from "$lib/design-system";
  import { Button } from "$lib/components/ui";
  import { X } from "@lucide/svelte";

  function toneFor(variant: string): Tone {
    if (variant === "danger") return "error";
    if (variant === "warning") return "warning";
    if (variant === "info") return "info";
    return "success";
  }
</script>

<div class="toast-stack" role="status" aria-live="polite">
  {#each toasts.toasts as t (t.id)}
    <div
      class="toast-wrap"
      role={t.variant === "danger" ? "alert" : "status"}
      aria-live={t.variant === "danger" ? "assertive" : "polite"}
    >
      <FeedbackBanner tone={toneFor(t.variant)} size="sm">
        <div class="flex items-start gap-3">
          <div class="flex flex-1 flex-col gap-0.5">
            <strong class="font-semibold">{t.title}</strong>
            {#if t.body}
              <span class="text-fg-muted">{t.body}</span>
            {/if}
          </div>
          <Button
            variant="ghost"
            size="icon"
            class="h-6 w-6"
            aria-label="Dismiss notification"
            onclick={() => toasts.dismiss(t.id)}
          >
            <X size={14} />
          </Button>
        </div>
      </FeedbackBanner>
    </div>
  {/each}
</div>

<style>
  /* Outer positioning stays vanilla CSS — Tailwind utilities are
     fine here too but z-index + fixed + the pointer-events trick
     (container ignores, items accept) read clearer in vanilla. */
  .toast-stack {
    position: fixed;
    right: var(--space-4);
    bottom: var(--space-4);
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    z-index: 2000;
    pointer-events: none;
    min-width: 280px;
    max-width: 360px;
  }
  .toast-wrap {
    pointer-events: auto;
    animation: saiku-panel-in var(--duration-normal) ease;
  }
</style>

<script lang="ts">
  import { browser } from "$app/environment";
  import { Button } from "$lib/components/ui";
  import { onMount } from "svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { session } from "$lib/stores/session.svelte";

  interface Step {
    selector: string;
    titleKey: string;
    bodyKey: string;
  }

  const STEPS: Step[] = [
    { selector: "#cubes-select", titleKey: "tour.pickCube.title", bodyKey: "tour.pickCube.body" },
    { selector: ".workspace__sidebar", titleKey: "tour.measDim.title", bodyKey: "tour.measDim.body" },
    { selector: ".dropzone", titleKey: "tour.build.title", bodyKey: "tour.build.body" },
    { selector: ".view-toggle", titleKey: "tour.gridChart.title", bodyKey: "tour.gridChart.body" },
  ];

  /** Per-user storage key — a second user on the same browser should
   *  get their own first-time experience. Falls back to a global key
   *  for anonymous / pre-session bootstrap (which shouldn't ever
   *  reach this code path, since the parent route gates on
   *  session.current). 2026-06-08. */
  const STORAGE_KEY_FALLBACK = "saiku.tour.done";
  function storageKey(): string {
    const u = session.current?.username;
    return u ? `saiku.tour.done.${u}` : STORAGE_KEY_FALLBACK;
  }

  let active = $state<boolean>(false);
  let stepIdx = $state<number>(0);
  let anchor = $state<DOMRect | null>(null);

  function updateAnchor() {
    if (!browser) return;
    const sel = STEPS[stepIdx]?.selector;
    const el = sel ? document.querySelector(sel) : null;
    anchor = el ? el.getBoundingClientRect() : null;
  }

  /** The first step's target — the cubes select — only exists on the
   *  workspace route. If it's missing the user is on a route where
   *  the tour wouldn't anchor anywhere meaningful, so don't start. */
  function workspaceVisible(): boolean {
    return !!document.querySelector(STEPS[0].selector);
  }

  onMount(() => {
    if (!browser) return;
    if (localStorage.getItem(storageKey()) === "1") return;
    // Migration: an earlier release wrote the flag under a global key.
    // Honour it so users who already finished the tour aren't shown it
    // again after the per-user split lands.
    if (localStorage.getItem(STORAGE_KEY_FALLBACK) === "1") {
      localStorage.setItem(storageKey(), "1");
      return;
    }
    // Wait a tick for the workspace render then bail if it isn't here.
    requestAnimationFrame(() => {
      if (!workspaceVisible()) return;
      active = true;
      updateAnchor();
    });
    const onResize = () => updateAnchor();
    window.addEventListener("resize", onResize);
    window.addEventListener("scroll", onResize, true);
    return () => {
      window.removeEventListener("resize", onResize);
      window.removeEventListener("scroll", onResize, true);
    };
  });

  $effect(() => {
    if (active) updateAnchor();
  });

  function next() {
    if (stepIdx < STEPS.length - 1) {
      stepIdx += 1;
      requestAnimationFrame(updateAnchor);
    } else {
      finish();
    }
  }

  function prev() {
    if (stepIdx > 0) {
      stepIdx -= 1;
      requestAnimationFrame(updateAnchor);
    }
  }

  function skip() {
    finish();
  }

  function finish() {
    active = false;
    if (browser) localStorage.setItem(storageKey(), "1");
  }

  export function restart() {
    if (!browser) return;
    localStorage.removeItem(storageKey());
    stepIdx = 0;
    active = true;
    requestAnimationFrame(updateAnchor);
  }
</script>

{#if active}
  <div class="tour" role="dialog" aria-modal="true" aria-label={i18n.t(STEPS[stepIdx].titleKey)}>
    {#if anchor}
      <div
        class="tour__highlight"
        style="
          top: {anchor.top - 4}px;
          left: {anchor.left - 4}px;
          width: {anchor.width + 8}px;
          height: {anchor.height + 8}px;
        "
      ></div>
    {/if}
    <div
      class="tour__bubble"
      style={anchor
        ? `top: ${anchor.bottom + 12}px; left: ${Math.max(12, Math.min(anchor.left, window.innerWidth - 360))}px;`
        : "top: 40%; left: 50%; transform: translate(-50%, -50%);"}
    >
      <header>
        <strong>{i18n.t(STEPS[stepIdx].titleKey)}</strong>
        <span class="step">{stepIdx + 1} / {STEPS.length}</span>
      </header>
      <p>{i18n.t(STEPS[stepIdx].bodyKey)}</p>
      <footer>
        <Button variant="outline" onclick={skip}>
          {i18n.t("tour.skip")}
        </Button>
        {#if stepIdx > 0}
          <Button variant="outline" onclick={prev}>{i18n.t("tour.back")}</Button>
        {/if}
        <Button onclick={next}>
          {stepIdx === STEPS.length - 1 ? i18n.t("tour.done") : i18n.t("tour.next")}
        </Button>
      </footer>
    </div>
  </div>
{/if}

<style>
  .tour { position: fixed; inset: 0; z-index: 3000; pointer-events: none; }
  .tour__highlight {
    position: absolute;
    border: 2px solid var(--accent);
    border-radius: var(--radius-sm);
    box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.45);
    transition: all var(--duration-normal) ease;
    pointer-events: none;
  }
  .tour__bubble {
    position: absolute;
    width: 360px;
    max-width: calc(100vw - 24px);
    background: var(--bg);
    color: var(--fg);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    box-shadow: var(--shadow-lg);
    padding: var(--space-4);
    pointer-events: auto;
  }
  .tour__bubble header {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    margin-bottom: var(--space-2);
  }
  .tour__bubble .step { color: var(--fg-subtle); font-size: var(--fs-xs); }
  .tour__bubble p { margin: 0 0 var(--space-3); color: var(--fg-muted); font-size: var(--fs-sm); }
  .tour__bubble footer {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
  }
</style>

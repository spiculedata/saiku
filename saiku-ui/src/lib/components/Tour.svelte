<script lang="ts">
  import { browser } from "$app/environment";
  import { onMount } from "svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  interface Step {
    selector: string;
    title: string;
    body: string;
  }

  const STEPS: Step[] = [
    {
      selector: "#cubes-select",
      title: "Pick a cube",
      body: "Datasources and cubes live here. Pick one to browse its measures and dimensions.",
    },
    {
      selector: ".workspace__sidebar",
      title: "Measures and dimensions",
      body: "Drag a measure or a level from the sidebar onto the axes to the right.",
    },
    {
      selector: ".dropzone",
      title: "Build a query",
      body: "Drop levels onto Rows/Columns and measures onto Columns. Toggle Autorun to re-run on every change.",
    },
    {
      selector: ".view-toggle",
      title: "Grid or chart",
      body: "Switch between the pivot grid and any of ten chart types, any time after you've run the query.",
    },
  ];

  const STORAGE_KEY = "saiku.tour.done";

  let active = $state<boolean>(false);
  let stepIdx = $state<number>(0);
  let anchor = $state<DOMRect | null>(null);

  function updateAnchor() {
    if (!browser) return;
    const sel = STEPS[stepIdx]?.selector;
    const el = sel ? document.querySelector(sel) : null;
    anchor = el ? el.getBoundingClientRect() : null;
  }

  onMount(() => {
    if (!browser) return;
    if (localStorage.getItem(STORAGE_KEY) === "1") return;
    active = true;
    requestAnimationFrame(updateAnchor);
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
    if (browser) localStorage.setItem(STORAGE_KEY, "1");
  }

  export function restart() {
    if (!browser) return;
    localStorage.removeItem(STORAGE_KEY);
    stepIdx = 0;
    active = true;
    requestAnimationFrame(updateAnchor);
  }
</script>

{#if active}
  <div class="tour" role="dialog" aria-modal="true" aria-label={STEPS[stepIdx].title}>
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
        <strong>{STEPS[stepIdx].title}</strong>
        <span class="step">{stepIdx + 1} / {STEPS.length}</span>
      </header>
      <p>{STEPS[stepIdx].body}</p>
      <footer>
        <button type="button" class="btn" onclick={skip}>
          {i18n.t("modal.close", "Skip")}
        </button>
        {#if stepIdx > 0}
          <button type="button" class="btn" onclick={prev}>Back</button>
        {/if}
        <button type="button" class="btn btn--primary" onclick={next}>
          {stepIdx === STEPS.length - 1 ? "Done" : "Next"}
        </button>
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

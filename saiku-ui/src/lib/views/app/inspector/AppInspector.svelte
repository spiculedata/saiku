<script lang="ts">
  /*
   * App Inspector — the graphical authoring surface for everything that used to
   * be hand-edited JSON: theme, header chrome, navigation, the assistant, and
   * page meta. A tabbed right-edge panel (edit mode only). Sections write
   * through the appDoc store so the live app re-renders instantly.
   *
   * `initialSection` lets a click on a live element (header / rail) open the
   * inspector directly on the relevant tab — the selection model (Phase C).
   */
  import { X } from "lucide-svelte";
  import ThemeSection from "$lib/views/app/inspector/ThemeSection.svelte";
  import HeaderSection from "$lib/views/app/inspector/HeaderSection.svelte";
  import NavSection from "$lib/views/app/inspector/NavSection.svelte";
  import AssistantSection from "$lib/views/app/inspector/AssistantSection.svelte";
  import PagesSection from "$lib/views/app/inspector/PagesSection.svelte";

  export type InspectorSection = "theme" | "header" | "nav" | "assistant" | "pages";

  interface Props {
    onClose: () => void;
    initialSection?: InspectorSection;
  }
  let { onClose, initialSection = "theme" }: Props = $props();

  // Seed from the prop; the $effect below re-syncs when a selection re-opens
  // the inspector on a different tab.
  // svelte-ignore state_referenced_locally
  let section = $state<InspectorSection>(initialSection);
  // Re-sync when a selection re-opens the inspector on a different tab.
  $effect(() => {
    section = initialSection;
  });

  const TABS: { k: InspectorSection; label: string }[] = [
    { k: "theme", label: "Theme" },
    { k: "header", label: "Header" },
    { k: "nav", label: "Nav" },
    { k: "assistant", label: "Assistant" },
    { k: "pages", label: "Pages" },
  ];
</script>

<aside class="insp" aria-label="App inspector">
  <header class="insp__head">
    <div class="insp__tabs" role="tablist">
      {#each TABS as t (t.k)}
        <button
          type="button"
          role="tab"
          class="insp__tab"
          class:is-active={section === t.k}
          aria-selected={section === t.k}
          onclick={() => (section = t.k)}>{t.label}</button>
      {/each}
    </div>
    <button type="button" class="insp__close" aria-label="Close inspector" onclick={onClose}><X size={16} /></button>
  </header>

  <div class="insp__body">
    {#if section === "theme"}
      <ThemeSection />
    {:else if section === "header"}
      <HeaderSection />
    {:else if section === "nav"}
      <NavSection />
    {:else if section === "assistant"}
      <AssistantSection />
    {:else if section === "pages"}
      <PagesSection />
    {/if}
  </div>
</aside>

<style>
  .insp {
    width: 21rem;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    min-height: 0;
    height: 100%;
    background: hsl(var(--bg));
    border-left: 1px solid hsl(var(--border));
    box-sizing: border-box;
  }
  .insp__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    padding: 0.5rem 0.5rem 0.5rem 0.75rem;
    border-bottom: 1px solid hsl(var(--border));
  }
  .insp__tabs {
    display: flex;
    gap: 1px;
    overflow-x: auto;
  }
  .insp__tab {
    border: 0;
    background: transparent;
    color: hsl(var(--fg-muted));
    font-size: 0.78rem;
    font-weight: 600;
    padding: 0.35rem 0.5rem;
    border-radius: 6px;
    cursor: pointer;
    white-space: nowrap;
  }
  .insp__tab.is-active {
    background: hsl(var(--bg-subtle));
    color: hsl(var(--fg));
  }
  .insp__close {
    border: 0;
    background: transparent;
    color: hsl(var(--fg-muted));
    cursor: pointer;
    display: inline-flex;
    padding: 4px;
    border-radius: 6px;
    flex-shrink: 0;
  }
  .insp__close:hover {
    background: hsl(var(--bg-hover));
  }
  .insp__body {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 0.75rem 1rem 1.5rem;
  }

  /* ---- shared control kit (global so section components can use it) ---- */
  :global(.insp .insp-section) {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    margin-bottom: 1.25rem;
  }
  :global(.insp .insp-label) {
    font-size: 0.66rem;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: hsl(var(--fg-muted));
  }
  :global(.insp .insp-row) {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    font-size: 0.82rem;
  }
  :global(.insp .insp-row > span) {
    color: hsl(var(--fg));
  }
  :global(.insp .insp-input),
  :global(.insp .insp-select) {
    flex: 1;
    min-width: 0;
    max-width: 62%;
    padding: 0.32rem 0.45rem;
    border: 1px solid hsl(var(--border));
    border-radius: 6px;
    background: hsl(var(--bg));
    color: hsl(var(--fg));
    font: inherit;
    font-size: 0.8rem;
    box-sizing: border-box;
  }
  :global(.insp .insp-input--full) {
    max-width: none;
    width: 100%;
  }
  :global(.insp .insp-textarea) {
    width: 100%;
    box-sizing: border-box;
    padding: 0.5rem;
    border: 1px solid hsl(var(--border));
    border-radius: 6px;
    background: hsl(var(--bg));
    color: hsl(var(--fg));
    font: inherit;
    font-size: 0.8rem;
    resize: vertical;
  }
  :global(.insp .insp-hint) {
    margin: 0;
    font-size: 0.72rem;
    color: hsl(var(--fg-muted));
    line-height: 1.4;
  }
  :global(.insp .insp-seg) {
    display: inline-flex;
    border: 1px solid hsl(var(--border));
    border-radius: 6px;
    overflow: hidden;
    flex-shrink: 0;
  }
  :global(.insp .insp-seg button) {
    border: 0;
    background: hsl(var(--bg));
    color: hsl(var(--fg-muted));
    padding: 0.28rem 0.55rem;
    font-size: 0.72rem;
    text-transform: capitalize;
    cursor: pointer;
    border-left: 1px solid hsl(var(--border));
  }
  :global(.insp .insp-seg button:first-child) {
    border-left: 0;
  }
  :global(.insp .insp-seg button.is-active) {
    background: hsl(var(--primary));
    color: var(--accent-fg, #fff);
  }
  :global(.insp .insp-toggle) {
    cursor: pointer;
  }
  :global(.insp .insp-list) {
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
  }
  :global(.insp .insp-list-item) {
    display: flex;
    gap: 0.35rem;
    align-items: center;
  }
  :global(.insp .insp-list-item .insp-input) {
    max-width: none;
  }
  :global(.insp .insp-iconbtn) {
    border: 1px solid hsl(var(--border));
    background: hsl(var(--bg));
    color: hsl(var(--fg-muted));
    border-radius: 6px;
    padding: 0.3rem 0.45rem;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    flex-shrink: 0;
  }
  :global(.insp .insp-iconbtn:hover) {
    background: hsl(var(--bg-hover));
    color: hsl(var(--fg));
  }
  :global(.insp .insp-addbtn) {
    align-self: flex-start;
    border: 1px dashed var(--border-strong, hsl(var(--border)));
    background: transparent;
    color: hsl(var(--fg-muted));
    border-radius: 6px;
    padding: 0.3rem 0.6rem;
    font-size: 0.76rem;
    cursor: pointer;
  }
  :global(.insp .insp-addbtn:hover) {
    color: hsl(var(--fg));
    border-color: hsl(var(--primary));
  }
</style>

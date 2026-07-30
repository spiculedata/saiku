<script lang="ts">
  /*
   * Branded app header: logo (or name fallback) on the left, app name, and a
   * caller-supplied `controls` snippet on the right. The controls slot is where
   * later tasks drop share / present / theme buttons — this component just
   * reserves and renders it.
   */
  import type { Snippet } from "svelte";
  import type { SaikuApp } from "$lib/api/apps";

  interface Props {
    app: SaikuApp;
    /** Right-aligned controls (share / present / theme). Wired by later tasks. */
    controls?: Snippet;
  }

  let { app, controls }: Props = $props();
</script>

<header class="saiku-app__header">
  <div class="saiku-app__brand">
    {#if app.logo}
      <img class="saiku-app__logo" src={app.logo} alt={app.name} />
    {/if}
    <span class="saiku-app__name">{app.name}</span>
  </div>
  <div class="saiku-app__controls">
    {@render controls?.()}
  </div>
</header>

<style>
  .saiku-app__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    padding: 0.5rem 1rem;
    min-height: 3rem;
    box-sizing: border-box;
    background: var(--saiku-app-bg, var(--bg));
    color: var(--saiku-app-fg, var(--fg));
    border-bottom: 1px solid var(--border);
    font-family: var(--saiku-app-font, inherit);
  }
  .saiku-app__brand {
    display: flex;
    align-items: center;
    gap: 0.625rem;
    min-width: 0;
  }
  .saiku-app__logo {
    height: 1.75rem;
    max-width: 8rem;
    object-fit: contain;
    display: block;
  }
  .saiku-app__name {
    font-weight: 600;
    font-size: 0.9375rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--saiku-app-primary, var(--saiku-app-fg, var(--fg)));
  }
  .saiku-app__controls {
    display: flex;
    align-items: center;
    gap: 0.375rem;
    flex-shrink: 0;
  }
</style>

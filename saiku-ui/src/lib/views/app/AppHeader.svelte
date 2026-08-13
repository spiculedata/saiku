<script lang="ts">
  /*
   * Branded app header. Renders a two-tone wordmark (an accent substring of the
   * app name), an optional uppercase eyebrow after a divider, and — right-
   * aligned — an optional context pill (tiny label over a bold value) and a
   * live-status badge, followed by the caller-supplied `controls` snippet.
   *
   * All of the header chrome is config-driven (SaikuApp.header) so a design can
   * be pixel-ported without custom CSS. When no header config is present it
   * falls back to a plain app name + logo.
   */
  import type { Snippet } from "svelte";
  import type { SaikuApp } from "$lib/api/apps";
  import { ChevronDown } from "lucide-svelte";
  import { effectiveLabel, isSelectable, optionsFor } from "$lib/views/app/contextPill";
  import { badgeFor } from "$lib/views/app/liveBadge";
  import { appConnection } from "$lib/stores/appConnection.svelte";

  interface Props {
    app: SaikuApp;
    /** Right-aligned controls (share / present / theme). Wired by later tasks. */
    controls?: Snippet;
    /** Edit-mode selection: double-click the wordmark to edit the header. */
    onSelect?: () => void;
    /** Currently selected context-pill label (AppShell owns the state). */
    contextValue?: string;
    /** Fired when the viewer picks a different context option. */
    onContextChange?: (label: string) => void;
  }

  let { app, controls, onSelect, contextValue, onContextChange }: Props = $props();

  const header = $derived(app.header ?? {});
  const pill = $derived(header.contextPill);
  const pillOptions = $derived(optionsFor(pill));
  const pillSelectable = $derived(isSelectable(pill));
  /** What the pill shows — the live selection when it's still on offer, else
   *  the configured default (see effectiveLabel). */
  const pillValue = $derived(effectiveLabel(pill, contextValue));

  // Poll only while a header is on screen; teardown stops the timer.
  $effect(() => appConnection.watch());
  const badge = $derived(badgeFor(header.liveBadge, appConnection.state));

  /** Split the app name around the first occurrence of the accent substring so
   *  the middle run can be coloured. Returns [before, accent, after]. */
  const wordmark = $derived.by<[string, string, string]>(() => {
    const name = app.name;
    const acc = header.wordmarkAccent;
    if (!acc) return [name, "", ""];
    const i = name.indexOf(acc);
    if (i === -1) return [name, "", ""];
    return [name.slice(0, i), acc, name.slice(i + acc.length)];
  });
</script>

<header class="saiku-app__header">
  <!-- svelte-ignore a11y_no_static_element_interactions a11y_click_events_have_key_events -->
  <div
    class="saiku-app__brand"
    class:saiku-app__brand--editable={onSelect}
    title={onSelect ? "Double-click to edit header" : undefined}
    ondblclick={onSelect}
  >
    {#if app.logo}
      <img class="saiku-app__logo" src={app.logo} alt={app.name} />
    {/if}
    {#if header.wordmarkAccent}
      <span class="saiku-app__name">{wordmark[0]}<span class="saiku-app__name-accent"
          >{wordmark[1]}</span>{wordmark[2]}</span>
    {:else}
      <span class="saiku-app__name">{app.name}</span>
    {/if}
    {#if header.eyebrow}
      <span class="saiku-app__divider" aria-hidden="true"></span>
      <span class="saiku-app__eyebrow">{header.eyebrow}</span>
    {/if}
  </div>

  <div class="saiku-app__controls">
    {#if pill}
      <!-- With options the pill is a real control: a transparent native <select>
           is layered over the styled face, so it keeps the design while getting
           the platform's keyboard handling, focus ring and mobile picker for
           free (the same trick the reference mock-up uses). -->
      <div class="saiku-app__ctxpill" class:saiku-app__ctxpill--live={pillSelectable} title={pillValue}>
        <span class="saiku-app__ctxpill-label">{pill.label}</span>
        <span class="saiku-app__ctxpill-value"
          >{pillValue}<ChevronDown size={13} aria-hidden="true" /></span>
        {#if pillSelectable}
          <select
            class="saiku-app__ctxpill-select"
            aria-label={pill.label}
            value={pillValue}
            onchange={(e) => onContextChange?.((e.currentTarget as HTMLSelectElement).value)}
          >
            {#each pillOptions as o (o.label)}
              <option value={o.label}>{o.label}</option>
            {/each}
          </select>
        {/if}
      </div>
    {/if}
    {#if badge}
      <!-- Reflects a real probe (see appConnection): green when connected,
           amber on a demo instance, red when Saiku can't be reached. -->
      <span class="saiku-app__livebadge" data-tone={badge.tone} title={badge.hint}>
        <span class="saiku-app__livedot" aria-hidden="true"></span>{badge.text}
      </span>
    {/if}
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
    background: var(--saiku-app-bg, hsl(var(--bg)));
    color: var(--saiku-app-fg, hsl(var(--fg)));
    border-bottom: 1px solid hsl(var(--border));
    font-family: var(--saiku-app-font, inherit);
  }
  .saiku-app__brand {
    display: flex;
    align-items: center;
    gap: 0.625rem;
    min-width: 0;
  }
  .saiku-app__brand--editable {
    cursor: pointer;
    border-radius: 6px;
  }
  .saiku-app__brand--editable:hover {
    outline: 1px dashed var(--saiku-app-accent, #888);
    outline-offset: 3px;
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
    color: var(--saiku-app-primary, var(--saiku-app-fg, hsl(var(--fg))));
  }
  .saiku-app__name-accent {
    color: var(--saiku-app-accent-2, var(--saiku-app-accent, #c85a3a));
  }
  .saiku-app__divider {
    width: 1px;
    height: 1.05rem;
    background: var(--border-strong, #d8cfba);
    flex-shrink: 0;
  }
  .saiku-app__eyebrow {
    font-family: -apple-system, "Segoe UI", sans-serif;
    font-size: 0.62rem;
    font-weight: 600;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--saiku-app-muted, #a99e82);
    white-space: nowrap;
  }
  .saiku-app__controls {
    display: flex;
    align-items: center;
    gap: 0.625rem;
    flex-shrink: 0;
  }
  /* Context pill — tiny uppercase label over a bold value + chevron. */
  .saiku-app__ctxpill {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 1px;
    padding: 5px 13px;
    border-radius: 10px;
    background: var(--saiku-app-card, #fff);
    border: 1px solid var(--border, #e4dcca);
    box-shadow: 0 1px 2px rgba(30, 40, 30, 0.05);
    font-family: -apple-system, "Segoe UI", sans-serif;
    line-height: 1.1;
  }
  .saiku-app__ctxpill--live {
    cursor: pointer;
  }
  .saiku-app__ctxpill--live:hover {
    border-color: var(--saiku-app-accent, #c85a3a);
  }
  /* Invisible but fully functional: the real control sits over the styled face
     so the design survives while the platform supplies the behaviour. */
  .saiku-app__ctxpill-select {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    opacity: 0;
    cursor: pointer;
    border: 0;
    /* 16px avoids the iOS zoom-on-focus. */
    font-size: 16px;
  }
  /* The native focus ring lands on the transparent <select>, so mirror it onto
     the visible face — otherwise keyboard focus is invisible. */
  .saiku-app__ctxpill:focus-within {
    outline: 2px solid var(--saiku-app-accent, #2e5e43);
    outline-offset: 2px;
  }
  .saiku-app__ctxpill-label {
    font-size: 0.56rem;
    font-weight: 700;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--saiku-app-muted, #a99e82);
  }
  .saiku-app__ctxpill-value {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    font-size: 0.82rem;
    font-weight: 700;
    color: var(--saiku-app-fg, #26332b);
  }
  .saiku-app__livebadge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    border-radius: 999px;
    background: var(--saiku-app-accent-soft, #eaf3ec);
    color: var(--saiku-app-accent-strong, #2e5e43);
    /* Tone overrides below; the accent default covers "positive". */
    font-family: -apple-system, "Segoe UI", sans-serif;
    font-size: 0.72rem;
    font-weight: 700;
    letter-spacing: 0.01em;
    white-space: nowrap;
  }
  .saiku-app__livedot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: currentColor;
    flex-shrink: 0;
  }
  /* A failure has to be legible even in an app whose accent happens to be red
     or green, so these use fixed status colours rather than brand tokens. */
  .saiku-app__livebadge[data-tone="warning"] {
    background: #fdf1dd;
    color: #8a5a12;
  }
  .saiku-app__livebadge[data-tone="danger"] {
    background: #fdeceb;
    color: #a3271b;
  }
  .saiku-app__livebadge[data-tone="neutral"] {
    background: var(--saiku-app-ground, #f1f1ef);
    color: var(--saiku-app-muted, #7b7a75);
  }
</style>

<script lang="ts">
  /*
   * Brand & Theme inspector (Phase A of graphical authoring). A slide-in panel,
   * shown in edit mode, that lets an analyst brand an app with NO code:
   *   - pick a preset from the gallery,
   *   - tune colours / type / form with pickers,
   *   - (advanced, disclosed) drop into raw customCss.
   * Every change flows through appDoc (setTheme / applyPreset) so the live app
   * behind the panel re-themes instantly.
   */
  import { appDoc } from "$lib/stores/appDoc.svelte";
  import { THEME_PRESETS, resolveTokens, type ResolvedTokens } from "$lib/dashboard/appThemePresets";
  import { FONT_ALLOWLIST } from "$lib/dashboard/appTheme";
  import { X } from "lucide-svelte";

  interface Props {
    onClose: () => void;
  }
  let { onClose }: Props = $props();

  const theme = $derived(appDoc.current?.theme ?? { mode: "light" as const });
  // Effective (resolved) tokens — seeds the pickers even when the value comes
  // from the active preset rather than an explicit override.
  const tok = $derived<ResolvedTokens>(resolveTokens(theme));

  const COLOURS: { k: keyof ResolvedTokens; label: string }[] = [
    { k: "accent", label: "Accent" },
    { k: "accent2", label: "Brand mark" },
    { k: "ground", label: "Background" },
    { k: "surface", label: "Cards" },
    { k: "fg", label: "Text" },
    { k: "positive", label: "Up / positive" },
    { k: "danger", label: "Down / negative" },
  ];
  const RADII: ResolvedTokens["radius"][] = ["none", "sm", "md", "lg", "xl"];
  const SHADOWS: ResolvedTokens["shadow"][] = ["none", "sm", "md", "lg"];
  const DENSITIES: ResolvedTokens["density"][] = ["compact", "cozy", "comfortable"];

  let advancedOpen = $state(false);

  function setToken(k: keyof ResolvedTokens, v: string): void {
    appDoc.setTheme({ [k]: v });
  }
  function setCss(v: string): void {
    appDoc.setTheme({ customCss: v });
  }
</script>

<aside class="bt" aria-label="Brand & Theme">
  <header class="bt__head">
    <h2>Brand &amp; Theme</h2>
    <button type="button" class="bt__close" aria-label="Close" onclick={onClose}><X size={16} /></button>
  </header>

  <div class="bt__body">
    <!-- Preset gallery -->
    <section class="bt__section">
      <div class="bt__label">Preset</div>
      <div class="bt__presets">
        {#each THEME_PRESETS as p (p.key)}
          <button
            type="button"
            class="bt__preset"
            class:is-active={theme.preset === p.key}
            title={p.note}
            onclick={() => appDoc.applyPreset(p.key)}
          >
            <span class="bt__swatches" aria-hidden="true">
              <span style="background:{p.tokens.ground}"></span>
              <span style="background:{p.tokens.surface}; border:1px solid {p.tokens.cardBorder}"></span>
              <span style="background:{p.tokens.accent}"></span>
              <span style="background:{p.tokens.accent2}"></span>
            </span>
            <span class="bt__preset-name">{p.label}</span>
          </button>
        {/each}
      </div>
    </section>

    <!-- Colours -->
    <section class="bt__section">
      <div class="bt__label">Colours</div>
      <div class="bt__colours">
        {#each COLOURS as c (c.k)}
          <label class="bt__colour">
            <input
              type="color"
              value={String(tok[c.k])}
              oninput={(e) => setToken(c.k, (e.currentTarget as HTMLInputElement).value)}
            />
            <span>{c.label}</span>
          </label>
        {/each}
      </div>
    </section>

    <!-- Type -->
    <section class="bt__section">
      <div class="bt__label">Type</div>
      <label class="bt__row">
        <span>Headings</span>
        <select value={tok.fontDisplay} onchange={(e) => setToken("fontDisplay", (e.currentTarget as HTMLSelectElement).value)}>
          {#each FONT_ALLOWLIST as f (f.key)}<option value={f.key}>{f.label}</option>{/each}
        </select>
      </label>
      <label class="bt__row">
        <span>Body</span>
        <select value={tok.fontBody} onchange={(e) => setToken("fontBody", (e.currentTarget as HTMLSelectElement).value)}>
          {#each FONT_ALLOWLIST as f (f.key)}<option value={f.key}>{f.label}</option>{/each}
        </select>
      </label>
    </section>

    <!-- Form -->
    <section class="bt__section">
      <div class="bt__label">Form</div>
      <div class="bt__row"><span>Corners</span>
        <div class="bt__seg">
          {#each RADII as r (r)}
            <button type="button" class:is-active={tok.radius === r} onclick={() => setToken("radius", r)}>{r}</button>
          {/each}
        </div>
      </div>
      <div class="bt__row"><span>Shadow</span>
        <div class="bt__seg">
          {#each SHADOWS as sh (sh)}
            <button type="button" class:is-active={tok.shadow === sh} onclick={() => setToken("shadow", sh)}>{sh}</button>
          {/each}
        </div>
      </div>
      <div class="bt__row"><span>Density</span>
        <div class="bt__seg">
          {#each DENSITIES as d (d)}
            <button type="button" class:is-active={tok.density === d} onclick={() => setToken("density", d)}>{d}</button>
          {/each}
        </div>
      </div>
    </section>

    <!-- Advanced -->
    <section class="bt__section">
      <button type="button" class="bt__disclosure" aria-expanded={advancedOpen} onclick={() => (advancedOpen = !advancedOpen)}>
        {advancedOpen ? "▾" : "▸"} Advanced — custom CSS
      </button>
      {#if advancedOpen}
        <p class="bt__hint">Escape hatch for the long tail. Sanitised + scoped to this app. You shouldn't need this for standard branding.</p>
        <textarea
          class="bt__css"
          rows="8"
          spellcheck="false"
          placeholder="/* scoped custom CSS */"
          value={theme.customCss ?? ""}
          oninput={(e) => setCss((e.currentTarget as HTMLTextAreaElement).value)}
        ></textarea>
      {/if}
    </section>
  </div>
</aside>

<style>
  .bt {
    width: 20rem;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    min-height: 0;
    background: var(--bg);
    border-left: 1px solid var(--border);
    box-sizing: border-box;
  }
  .bt__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0.75rem 1rem;
    border-bottom: 1px solid var(--border);
  }
  .bt__head h2 {
    margin: 0;
    font-size: 0.95rem;
    font-weight: 600;
  }
  .bt__close {
    border: 0;
    background: transparent;
    color: var(--fg-muted);
    cursor: pointer;
    display: inline-flex;
    padding: 4px;
    border-radius: 6px;
  }
  .bt__close:hover {
    background: var(--bg-hover);
  }
  .bt__body {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 0.5rem 1rem 1.5rem;
    display: flex;
    flex-direction: column;
    gap: 1.25rem;
  }
  .bt__section {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }
  .bt__label {
    font-size: 0.66rem;
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--fg-muted);
  }
  .bt__presets {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.5rem;
  }
  .bt__preset {
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
    padding: 0.5rem;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--bg-subtle);
    cursor: pointer;
    text-align: left;
  }
  .bt__preset.is-active {
    border-color: var(--accent);
    box-shadow: 0 0 0 1px var(--accent);
  }
  .bt__swatches {
    display: flex;
    height: 22px;
    border-radius: 5px;
    overflow: hidden;
  }
  .bt__swatches span {
    flex: 1;
  }
  .bt__preset-name {
    font-size: 0.8rem;
    font-weight: 600;
  }
  .bt__colours {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.5rem 0.75rem;
  }
  .bt__colour {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    font-size: 0.8rem;
  }
  .bt__colour input[type="color"] {
    width: 28px;
    height: 24px;
    padding: 0;
    border: 1px solid var(--border);
    border-radius: 5px;
    background: none;
    cursor: pointer;
    flex-shrink: 0;
  }
  .bt__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    font-size: 0.82rem;
  }
  .bt__row select {
    flex: 1;
    max-width: 60%;
    padding: 0.3rem 0.4rem;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
    color: var(--fg);
    font: inherit;
    font-size: 0.8rem;
  }
  .bt__seg {
    display: inline-flex;
    border: 1px solid var(--border);
    border-radius: 6px;
    overflow: hidden;
  }
  .bt__seg button {
    border: 0;
    background: var(--bg);
    color: var(--fg-muted);
    padding: 0.28rem 0.5rem;
    font-size: 0.7rem;
    text-transform: capitalize;
    cursor: pointer;
    border-left: 1px solid var(--border);
  }
  .bt__seg button:first-child {
    border-left: 0;
  }
  .bt__seg button.is-active {
    background: var(--accent);
    color: var(--accent-fg, #fff);
  }
  .bt__disclosure {
    border: 0;
    background: transparent;
    color: var(--fg-muted);
    font-size: 0.78rem;
    font-weight: 600;
    text-align: left;
    padding: 0;
    cursor: pointer;
  }
  .bt__hint {
    margin: 0.25rem 0;
    font-size: 0.72rem;
    color: var(--fg-muted);
    line-height: 1.4;
  }
  .bt__css {
    width: 100%;
    box-sizing: border-box;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.74rem;
    padding: 0.5rem;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg-subtle);
    color: var(--fg);
    resize: vertical;
  }
</style>

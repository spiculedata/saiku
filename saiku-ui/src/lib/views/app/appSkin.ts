/*
 * The built-in App Builder "skin" — a scoped stylesheet that maps the theme
 * TOKENS (the `--saiku-app-*` CSS vars serialised by appTheme.ts) onto the
 * standard app surfaces: ground, cards/tiles, headings, KPI tiles, tables,
 * nav rail and assistant.
 *
 * Phase A of graphical authoring: this replaces the hand-written `customCss`
 * that every branded app used to need. Pick a preset (or tune tokens) and the
 * app gets a polished, cohesive look with NO author CSS. The app's own
 * `customCss` is still injected AFTER this (higher precedence) as an advanced
 * escape hatch for the long tail.
 *
 * Everything is scoped under the app root selector (`[data-saiku-app="…"]`) so
 * it can never leak into the surrounding Saiku UI or a sibling app.
 */

/** Build the skin CSS for one app, scoped under {@code scope}
 *  (e.g. `[data-saiku-app="preview"]`). Pure string — the shell sets it as a
 *  <style> element's textContent (never {@html}). */
export function appSkinCss(scope: string): string {
  const s = scope;
  return `
${s} .saiku-app__body, ${s} .saiku-app__main, ${s} .app-page {
  background: var(--saiku-app-ground, hsl(var(--bg)));
}
${s} { font-family: var(--saiku-app-font-body, inherit); }

/* ---- page title band ---- */
${s} .app-page__heading {
  font-family: var(--saiku-app-font-display, var(--saiku-app-font-body, inherit));
  color: var(--saiku-app-fg, inherit);
}
${s} .app-page__subheading { color: var(--saiku-app-muted, #8a7f68); }
${s} .app-page__title-meta {
  color: var(--saiku-app-muted, #a99e82);
  text-transform: uppercase;
  font-size: .72rem;
  letter-spacing: .08em;
}

/* ---- cards / tiles ---- */
${s} .tile {
  background: var(--saiku-app-surface, #fff);
  border: 1px solid var(--saiku-app-card-border, #ece7db);
  border-radius: var(--saiku-app-radius, 14px);
  box-shadow: var(--saiku-app-shadow, 0 1px 2px rgba(20,28,40,.06));
}
${s} .tile-header { background: transparent; border-bottom: none; }

/* KPI tiles: uppercase caption label + big display value + inline sparkline */
${s} .tile:has(.kpi-tile) .tile-header span {
  font-family: var(--saiku-app-font-body, sans-serif);
  font-weight: 700; text-transform: uppercase; letter-spacing: .07em;
  font-size: .66rem; color: var(--saiku-app-muted, #93876c);
}
${s} .tile:has(.kpi-tile) .tile-header { padding: 12px 16px 0; }
${s} .kpi-tile {
  display: grid;
  grid-template-columns: 1fr 96px;
  grid-template-areas: "value spark" "delta spark";
  align-items: center; column-gap: 10px; padding: 8px 16px 14px;
}
${s} .kpi-tile .value {
  grid-area: value; align-self: end;
  /* Figures follow the "Numbers" type token, not the body font — a monospace
     stack gives the tabular, ledger-like headline data products use. */
  font-family: var(--saiku-app-font-numeric, var(--saiku-app-font-body, sans-serif));
  font-variant-numeric: tabular-nums;
  font-size: 1.9rem; font-weight: 750; letter-spacing: -.01em;
  color: var(--saiku-app-fg, #17241d);
}
${s} .kpi-tile .delta { grid-area: delta; align-self: start; white-space: nowrap; font-size: .76rem; font-weight: 650; }
/* The delta reads direction at a glance, so it takes the positive/negative
   tokens rather than inheriting body ink. */
${s} .kpi-tile .delta[data-tone="positive"] { color: var(--saiku-app-positive, #2e7d55); }
${s} .kpi-tile .delta[data-tone="negative"] { color: var(--saiku-app-danger, #c0492b); }
${s} .kpi-tile .delta[data-tone="flat"], ${s} .kpi-tile .delta--empty { color: var(--saiku-app-muted, #93876c); }
${s} .kpi-tile div[aria-hidden="true"] { grid-area: spark; width: 96px; align-self: center; }
${s} .tile:has(.kpi-tile[data-tone="negative"]) .value { color: var(--saiku-app-danger, #c0492b); }

/* Tone-coloured edge bar. Width is the token (0px when the theme's KPI accent
   is "none"), so this stays a single unconditional rule. */
${s} .tile:has(.kpi-tile) { position: relative; overflow: hidden; }
${s} .tile:has(.kpi-tile)::before {
  content: ""; position: absolute; left: 0; top: 0; bottom: 0;
  width: var(--saiku-app-kpi-bar, 0px);
  background: var(--saiku-app-accent, #2e5e43);
  pointer-events: none;
}
${s} .tile:has(.kpi-tile[data-tone="positive"])::before { background: var(--saiku-app-positive, #2e7d55); }
${s} .tile:has(.kpi-tile[data-tone="negative"])::before { background: var(--saiku-app-danger, #c0492b); }
/* Chart cards hide their generic header in VIEW mode — the ECharts option
   carries its own title, so the generic one would double up. The guard is
   load-bearing: the tile header also carries the configure / menu / remove
   buttons, so hiding it unconditionally left chart tiles unreachable from the
   authoring UI (a freshly added Chart tile renders "open the gear to set a
   query" with no gear to open). AppShell stamps data-saiku-app-edit on the
   root while editing, which switches this rule off. */
${s}:not([data-saiku-app-edit]) .tile:not(:has(.kpi-tile)):not(:has(tbody)) .tile-header { display: none; }

/* ---- tables ---- */
${s} table { width: 100%; border-collapse: collapse; font-variant-numeric: tabular-nums; }
${s} thead th {
  text-transform: uppercase; font-size: .6rem; letter-spacing: .07em;
  color: var(--saiku-app-muted, #93876c); text-align: left;
  padding: 6px 14px 9px; border-bottom: 1px solid var(--saiku-app-card-border, #e8e1d2); font-weight: 700;
}
${s} thead th:last-child { text-align: right; }
${s} tbody th, ${s} tbody td {
  padding: 8px 14px; border-bottom: 1px solid var(--saiku-app-card-border, #f2ece0);
  font-size: .88rem; color: var(--saiku-app-fg, #2a352d);
}
${s} tbody th { font-weight: 500; text-align: left; }
${s} tbody td {
  text-align: right; font-weight: 650; color: var(--saiku-app-fg, #1f3529);
  font-family: var(--saiku-app-font-numeric, var(--saiku-app-font-body, sans-serif));
}

/* ---- nav rail ---- */
${s} .saiku-app__rail { background: var(--saiku-app-rail-bg, hsl(var(--bg-subtle))); border-right: none; padding-top: 14px; }
${s} .saiku-app__rail-brand { background: var(--saiku-app-accent-2, var(--saiku-app-accent, #2e5e43)); }
${s} .saiku-app__rail-item { color: var(--saiku-app-rail-fg, #9fb4a5); border-radius: 10px; }
${s} .saiku-app__rail-item.is-active { background: var(--saiku-app-accent, #2e5e43); color: #fff; }
${s} .saiku-app__rail-item:hover { background: rgba(255,255,255,.06); color: #fff; }
${s} .saiku-app__rail-collapse { display: none; }
`.trim();
}

/*
 * issue #1103 — host-page chart theming for <saiku-embed>.
 *
 * The host page styles the embedded CHART (not just the chrome) via CSS custom
 * properties on the element:
 *
 *   saiku-embed {
 *     --saiku-embed-chart-1: #2563eb;   ... up to --saiku-embed-chart-8
 *     --saiku-embed-fg:      #1f2937;    // axis labels / legend / titles
 *     --saiku-embed-muted:   #9ca3af;    // axis + split lines
 *   }
 *
 * CSS custom properties inherit THROUGH the shadow boundary, so they're
 * readable via getComputedStyle on any element inside the component. Pure: the
 * reader takes a `(varName) => value` lookup so it unit-tests without a DOM.
 */

export interface EmbedChartTheme {
  /** Series colour cycle, in order. Empty → ECharts' built-in palette (the
   *  pre-#1103 default, so an unstyled embed is unchanged). */
  palette: string[];
  /** Text colour for axis labels, legend and titles (`--saiku-embed-fg`). */
  fg?: string;
  /** Colour for axis + split lines (`--saiku-embed-muted`). */
  axisLine?: string;
}

/**
 * Resolve the chart theme from a CSS-var getter. The palette is read
 * **contiguously** from `--saiku-embed-chart-1` and stops at the first unset
 * index, so `1,2,3` set → a 3-colour cycle (a gap doesn't silently reorder).
 */
export function readEmbedChartTheme(getVar: (name: string) => string): EmbedChartTheme {
  const palette: string[] = [];
  for (let i = 1; i <= 8; i++) {
    const v = (getVar(`--saiku-embed-chart-${i}`) || "").trim();
    if (!v) break;
    palette.push(v);
  }
  const fg = (getVar("--saiku-embed-fg") || "").trim() || undefined;
  const axisLine = (getVar("--saiku-embed-muted") || "").trim() || undefined;
  return { palette, fg, axisLine };
}

/** Has the host actually set any chart theme var? (Lets the renderer skip
 *  emitting empty style objects when the embed is unstyled.) */
export function hasEmbedChartTheme(t: EmbedChartTheme): boolean {
  return t.palette.length > 0 || !!t.fg || !!t.axisLine;
}

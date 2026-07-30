/*
 * .saikuapp document model (UI-side schema authority).
 *
 * An "app" is a self-contained document that wraps an ordered set of
 * pages[], where each page carries an inline dashboard-layout object — the
 * EXACT shape today's dashboards use (see $lib/api/dashboards → Dashboard.layout).
 * That grid is kept OPAQUE here (`grid: unknown`) so the dashboard grid stays
 * the single schema authority for tile layout; the app layer only owns the
 * page/nav/theme envelope. The backend stores the whole doc opaquely.
 *
 * A classic dashboard is conceptually a 1-page app — appFromDashboard() wraps a
 * dashboard layout as page 0, which is the parity / back-compat guarantee.
 *
 * Mirrors the style of $lib/api/dashboards.ts (pure model + factory helpers).
 */

export interface SaikuApp {
  id: string;
  name: string;
  version: number;
  logo?: string | null;
  theme: AppTheme;
  nav: AppNav;
  assistantSlot: { enabled: boolean };
  pages: AppPage[];
  tags: string[];
}

export interface AppTheme {
  mode: "light" | "dark" | "auto";
  primary?: string;
  accent?: string;
  bg?: string;
  fg?: string;
  font?: string;
  customCss?: string;
}

export interface AppNav {
  position: "rail" | "top";
}

export interface AppPage {
  id: string;
  title: string;
  icon?: string;
  /** Inline dashboard-layout object — opaque here; the dashboard grid
   *  (see $lib/api/dashboards → DashboardLayout) is the schema authority. */
  grid: unknown;
}

let seq = 0;

/** Client-local id for a freshly-minted page/app before the first save.
 *  Stable-ish within a session; the backend assigns the durable id on save. */
function localId(prefix: string): string {
  seq += 1;
  return `${prefix}-${seq}-${Math.round(performance.now())}`;
}

/** A blank page carrying an empty (but valid) dashboard-layout grid. */
export function emptyPage(title = "Page 1"): AppPage {
  return { id: localId("page"), title, grid: { cols: 12, tiles: [] } };
}

/** Build an empty app with one blank page and default nav/theme, ready to be
 *  populated by the editor before the first save. */
export function emptyApp(name = "New app"): SaikuApp {
  return {
    id: "",
    name,
    version: 1,
    logo: null,
    theme: { mode: "auto" },
    nav: { position: "rail" },
    assistantSlot: { enabled: false },
    pages: [emptyPage("Overview")],
    tags: [],
  };
}

/** Parity / back-compat: wrap an existing dashboard layout as page 0 of a
 *  fresh single-page app. The layout is stored verbatim as the page's grid. */
export function appFromDashboard(name: string, dashboardLayout: unknown): SaikuApp {
  const app = emptyApp(name);
  return {
    ...app,
    pages: [{ id: localId("page"), title: name, grid: dashboardLayout }],
  };
}

/** Input to {@link normaliseApp} — a possibly-partial doc loaded from storage
 *  or hand-authored. Every field is defaulted so callers can pass raw JSON. */
export type SaikuAppInput = Partial<Omit<SaikuApp, "theme" | "nav" | "assistantSlot" | "pages">> & {
  theme?: Partial<AppTheme>;
  nav?: Partial<AppNav>;
  assistantSlot?: Partial<{ enabled: boolean }>;
  pages?: Partial<AppPage>[];
};

/** Fill missing fields with defaults so a raw / partial doc becomes a complete
 *  SaikuApp. Idempotent — normalising an already-normalised app is a no-op. */
export function normaliseApp(raw: SaikuAppInput): SaikuApp {
  return {
    id: raw.id ?? "",
    name: raw.name ?? "Untitled app",
    version: raw.version ?? 1,
    logo: raw.logo ?? null,
    theme: { mode: raw.theme?.mode ?? "auto", ...raw.theme },
    nav: { position: raw.nav?.position ?? "rail" },
    assistantSlot: { enabled: raw.assistantSlot?.enabled ?? false },
    pages: (raw.pages ?? []).map((p, i) => ({
      id: p.id ?? localId("page"),
      title: p.title ?? `Page ${i + 1}`,
      icon: p.icon,
      grid: p.grid ?? { cols: 12, tiles: [] },
    })),
    tags: raw.tags ?? [],
  };
}

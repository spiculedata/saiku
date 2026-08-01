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
 *
 * REST client at the foot of this file mirrors DashboardResource's client
 * (fetch + credentials:"include") and talks to AppResource under
 * {@code /rest/saiku/api/apps}.
 */

export interface SaikuApp {
  id: string;
  name: string;
  version: number;
  logo?: string | null;
  theme: AppTheme;
  nav: AppNav;
  header?: AppHeaderConfig;
  assistantSlot: AppAssistantSlot;
  pages: AppPage[];
  tags: string[];
}

/** Branded-header configuration — the primitives needed to pixel-port a
 *  product header without custom CSS: a two-tone wordmark, a right-aligned
 *  context pill (e.g. a store selector), and a live-status badge. All optional;
 *  when absent the header falls back to a plain {@link SaikuApp.name}. */
export interface AppHeaderConfig {
  /** Substring of {@link SaikuApp.name} rendered in the accent colour, e.g.
   *  "Mart" in "FoodMart Ops" → "Food<accent>Mart</accent> Ops". First match. */
  wordmarkAccent?: string;
  /** Small uppercase eyebrow after the wordmark (e.g. "Store Intelligence"),
   *  separated by a vertical divider. */
  eyebrow?: string;
  /** Right-aligned context pill: a tiny label over a bold value with a ▾. */
  contextPill?: { label: string; value: string };
  /** Right-aligned live-status badge text (rendered with a ● dot), e.g.
   *  "Live · Saiku". */
  liveBadge?: string;
}

/** The right-hand "Ask" assistant column. When enabled, the app renders an
 *  in-app natural-language chat scoped to {@link cube}, hitting /ai/ask. */
export interface AppAssistantSlot {
  enabled: boolean;
  /** Panel title, e.g. "FoodMart" → rendered as "Ask FoodMart". */
  title?: string;
  /** Persona label shown under the title, e.g. "Sales Analyst". */
  persona?: string;
  /** Small scope note after the persona, e.g. "scoped to your stores". */
  scope?: string;
  /** Opening assistant message (markdown-ish plain text). */
  greeting?: string;
  /** Suggested prompt chips under a "Try asking" heading. */
  suggestedPrompts?: string[];
  /** Extra chips rendered in a monospace "skill" style (⌘ prefix) — for
   *  named saved workflows the analyst can invoke by name. */
  skillPrompts?: string[];
  /** Header glyph: "sparkles" (default) or "crosshair" (a targeting reticle,
   *  matching a scoped-assistant look). */
  icon?: "sparkles" | "crosshair";
  /** Keyboard hint shown in the composer footer, e.g. "↵ to send · ⇧↵ new line". */
  footerHint?: string;
  /** Small right-aligned attribution in the composer footer, e.g. "powered by Saiku". */
  poweredBy?: string;
  /** Cube the assistant queries. Falls back to the first queryable tile's cube. */
  cube?: { connectionName: string; catalog: string; schema: string; cubeName: string };
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
  /** Start the left rail collapsed to icons-only (matches the compact
   *  section-rail look of the reference dashboards). */
  railCollapsed?: boolean;
  /** Pinned rail footer: a settings gear and/or a user-avatar disc showing
   *  {@code avatar} initials — the bottom-of-rail chrome most product shells
   *  carry. Omit for no footer. */
  footer?: { settings?: boolean; avatar?: string };
}

export interface AppPage {
  id: string;
  title: string;
  icon?: string;
  /** Optional page-title row shown above the grid (big heading + muted sub +
   *  right-aligned meta) — the "Portland #14 · Today / Regional manager view"
   *  band the reference dashboards open with. */
  heading?: string;
  subheading?: string;
  meta?: string;
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
  assistantSlot?: Partial<AppAssistantSlot>;
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
    nav: { ...raw.nav, position: raw.nav?.position ?? "rail" },
    ...(raw.header ? { header: raw.header } : {}),
    assistantSlot: { ...raw.assistantSlot, enabled: raw.assistantSlot?.enabled ?? false },
    pages: (raw.pages ?? []).map((p, i) => ({
      ...p,
      id: p.id ?? localId("page"),
      title: p.title ?? `Page ${i + 1}`,
      grid: p.grid ?? { cols: 12, tiles: [] },
    })),
    tags: raw.tags ?? [],
  };
}

/* =========================================================================
 * REST client — CRUD over AppResource (/saiku/api/apps).
 *
 * Mirrors $lib/api/dashboards.ts: bare fetch with credentials:"include" so the
 * session cookie rides along, JSON Accept, and a thrown Error carrying
 * `${path} -> ${status}` on any non-2xx. The CSRF header on POST / DELETE is
 * injected globally by the http.ts fetch interceptor — no per-call handling.
 * ========================================================================= */

const REST_BASE = "/rest/saiku/api/apps";

/** One row in the saved-apps catalogue. Matches the {@code RepositoryFileObject}
 *  shape AppResource#list returns (a flat list of {@code .saikuapp} files, each
 *  serialised via Jackson getters). {@code path} + {@code name} are the only
 *  fields the catalogue UI needs; the rest are carried through for #935-style
 *  owner/modified sorting when a list view wants them. */
export interface AppSummary {
  path: string;
  name: string;
  type?: "FILE" | "FOLDER";
  fileType?: string;
  id?: string;
  owner?: string | null;
  modified?: number;
}

/** List saved apps. Throws on any non-2xx. */
export async function listApps(): Promise<AppSummary[]> {
  const res = await fetch(REST_BASE, {
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`listApps -> ${res.status}`);
  return (await res.json()) as AppSummary[];
}

/** Load a raw app doc by repository path and normalise it into a complete
 *  {@link SaikuApp}. Throws on any non-2xx. */
export async function getApp(path: string): Promise<SaikuApp> {
  const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  const raw = (await res.json()) as SaikuAppInput;
  return normaliseApp(raw);
}

/** Save (create or overwrite) an app at {@code path}. Throws on any non-2xx. */
export async function saveApp(path: string, app: SaikuApp): Promise<void> {
  const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(app),
  });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
}

/** Delete the app at {@code path}. Throws on any non-2xx. */
export async function deleteApp(path: string): Promise<void> {
  const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
    method: "DELETE",
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
}

/** URL-encode each path segment but leave slashes as-is — AppResource binds
 *  {@code {path:.+}} which captures slashes natively (same as DashboardResource). */
function encodePath(path: string): string {
  return path.split("/").map(encodeURIComponent).join("/");
}

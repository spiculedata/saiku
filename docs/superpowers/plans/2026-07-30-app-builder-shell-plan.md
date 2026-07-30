# Saiku App Builder — Phase 1 (App Shell + Navigation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an author build a branded, multi-page "app" in Saiku — configurable nav (rail/top) + header + theme (tokens + safely-scoped custom CSS), where each page reuses the existing dashboard grid/tiles — saved as one self-contained `.saikuapp` document that shares/embeds as a single unit. Classic dashboards remain unchanged.

**Architecture:** A new `.saikuapp` JSON document wraps `pages[]`, each page carrying an inline dashboard-layout object (the *exact* shape today's dashboards use). The backend stores it opaquely (mirroring `DashboardResource`); the UI is the schema authority. A new `AppShell` renders header + nav + the active page via the existing dashboard grid renderer. A per-app theme applies via CSS variables; optional author custom CSS is scoped to the app root and sanitised (reject-on-violation) at save. A dashboard is a 1-page app, so a "New app from dashboard" import copies a `.saikudash` layout into `pages[0].grid`.

**Tech Stack:** Java 21 (JAX-RS/Jersey 3.1) for `AppResource`; Svelte 5 + TypeScript + Vite for the UI; Vitest for UI unit tests; Hegel (`saiku-proptest`, JDK 22) for the CSS-sanitiser property tests; the existing dashboard grid/tile components reused verbatim.

**Reference patterns (read before starting):**
- Backend CRUD: `saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/dashboards/DashboardResource.java` (opaque `JsonNode` passthrough, JCR repo storage, history/comments services).
- UI dashboard doc + store + editor: `saiku-ui/src/lib/api/dashboards.ts`, `saiku-ui/src/lib/views/dashboard/DashboardEditor.svelte`, `saiku-ui/src/lib/dashboard/**` (esp. `urlFilterState.ts`, `responsiveLayout.ts`).
- Embed: `saiku-ui/src/embed/saiku-embed.ts`, `EmbedDashboard.svelte`, `saiku-core/.../embed/EmbedViewResource.java`.
- CSS/HTML sanitisation precedent: `saiku-ui/src/lib/views/dashboard/tiles/TextTile.svelte` (DOMPurify config) — the sanitiser here follows the same reject-hostile posture for CSS.
- Design spec: `docs/superpowers/specs/2026-07-30-app-builder-shell-design.md`.

**Resolved open questions (per spec §9, confirmed):** page-level filters now + an optional app-level filter bar (app-level bar deferred to a later task/phase — page filters only in this plan); nav derived 1:1 from `pages[]` for v1; a new top-level **Apps** section in the repo browser; custom CSS is **designer-only** (not exposed on the embed surface) in Phase 1.

**Conventions:** run `mvn spotless:apply` before any Java commit; `npm run check` + `npm run lint` before any UI commit. Work on a feature branch `feature/app-builder-shell` off `development`. Commit after each task's tests pass.

---

## Task 0: Branch + scaffolding

**Files:**
- Create: `feature/app-builder-shell` branch

- [ ] **Step 1: Branch off development**

```bash
cd /Users/tombarber/Projects/saiku/saiku
git checkout development && git pull origin development
git checkout -b feature/app-builder-shell
```

- [ ] **Step 2: Confirm the UI dev loop runs**

Run: `cd saiku-ui && npm run check`
Expected: `0 ERRORS` (pre-existing warnings OK).

---

## Task 1: The `.saikuapp` document model (UI types) + round-trip test

**Files:**
- Create: `saiku-ui/src/lib/api/apps.ts`
- Create: `saiku-ui/src/lib/api/apps.test.ts`

The UI is the schema authority. Define the types and a normaliser that (a) round-trips and (b) upgrades a bare dashboard layout into a 1-page app (the parity/back-compat guarantee).

- [ ] **Step 1: Write the failing test** — `saiku-ui/src/lib/api/apps.test.ts`

```ts
import { describe, expect, test } from "vitest";
import { emptyApp, appFromDashboard, normaliseApp, type SaikuApp } from "./apps";

describe("saikuapp model", () => {
  test("emptyApp has one blank page and default nav/theme", () => {
    const app = emptyApp("My App");
    expect(app.name).toBe("My App");
    expect(app.pages).toHaveLength(1);
    expect(app.nav.position).toBe("rail");
    expect(app.theme.mode).toBe("auto");
  });

  test("appFromDashboard wraps a dashboard layout as page 0 (parity/back-compat)", () => {
    const layout = { cols: 12, tiles: [{ id: "t1", type: "kpi", x: 0, y: 0, w: 3, h: 2 }] };
    const app = appFromDashboard("Sales", layout);
    expect(app.pages).toHaveLength(1);
    expect(app.pages[0].grid).toEqual(layout);
    expect(app.pages[0].title).toBe("Sales");
  });

  test("normaliseApp is idempotent and fills missing fields", () => {
    const raw = { name: "X", pages: [{ title: "P1", grid: { cols: 12, tiles: [] } }] } as unknown as SaikuApp;
    const once = normaliseApp(raw);
    expect(normaliseApp(once)).toEqual(once);
    expect(once.nav.position).toBe("rail");
    expect(once.theme.mode).toBe("auto");
    expect(once.pages[0].id).toBeTruthy(); // ids backfilled
  });
});
```

- [ ] **Step 2: Run it — expect failure** (`apps.ts` doesn't exist)

Run: `cd saiku-ui && npx vitest run src/lib/api/apps.test.ts`
Expected: FAIL — cannot resolve `./apps`.

- [ ] **Step 3: Implement `saiku-ui/src/lib/api/apps.ts`**

```ts
/** The self-contained app document. The UI is the schema authority; the server stores it opaquely. */
export interface SaikuApp {
  id: string;
  name: string;
  version: number;
  logo?: string | null;
  theme: AppTheme;
  nav: AppNav;
  assistantSlot: { enabled: boolean }; // reserved for Phase 5; renders nothing yet
  pages: AppPage[];
  tags: string[];
}

export interface AppTheme {
  mode: "light" | "dark" | "auto";
  primary?: string;
  accent?: string;
  bg?: string;
  fg?: string;
  font?: string;       // key into a curated allowlist (see themeFont.ts)
  customCss?: string;  // raw author CSS; sanitised+scoped at render (see cssSanitiser.ts)
}

export interface AppNav {
  position: "rail" | "top";
}

/** A page carries an inline dashboard layout — the EXACT object today's dashboards use. */
export interface AppPage {
  id: string;
  title: string;
  icon?: string;
  grid: unknown; // DashboardLayout shape; kept opaque here so the grid stays the schema authority
}

let seq = 0;
function localId(prefix: string): string {
  // Deterministic-enough per session; ids are only stable within a doc, server assigns the doc id.
  seq += 1;
  return `${prefix}-${seq}-${Math.round(performance.now())}`;
}

export function emptyPage(title = "Page 1"): AppPage {
  return { id: localId("page"), title, grid: { cols: 12, tiles: [] } };
}

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

/** Wrap an existing dashboard layout as a 1-page app (import / back-compat path). */
export function appFromDashboard(name: string, dashboardLayout: unknown): SaikuApp {
  const app = emptyApp(name);
  app.pages = [{ id: localId("page"), title: name, grid: dashboardLayout }];
  return app;
}

/** Fill defaults + backfill ids so a hand-written / older doc is safe to render. Idempotent. */
export function normaliseApp(raw: SaikuApp): SaikuApp {
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
```

- [ ] **Step 4: Run — expect pass**

Run: `cd saiku-ui && npx vitest run src/lib/api/apps.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add saiku-ui/src/lib/api/apps.ts saiku-ui/src/lib/api/apps.test.ts
git commit -m "feat(app-builder): .saikuapp document model + round-trip tests"
```

---

## Task 2: Font allowlist + theme-token application

**Files:**
- Create: `saiku-ui/src/lib/dashboard/appTheme.ts`
- Create: `saiku-ui/src/lib/dashboard/appTheme.test.ts`

Turn an `AppTheme` into a set of CSS custom properties applied to the app root. Fonts come from a fixed allowlist (no arbitrary `@font-face`).

- [ ] **Step 1: Write the failing test** — `appTheme.test.ts`

```ts
import { describe, expect, test } from "vitest";
import { themeVars, resolveFont, FONT_ALLOWLIST } from "./appTheme";

describe("appTheme", () => {
  test("themeVars maps tokens to --saiku-app-* custom properties", () => {
    const vars = themeVars({ mode: "light", primary: "#2f5d3a", accent: "#e2725b" });
    expect(vars["--saiku-app-primary"]).toBe("#2f5d3a");
    expect(vars["--saiku-app-accent"]).toBe("#e2725b");
  });

  test("resolveFont only returns an allowlisted stack; unknown → default", () => {
    const first = FONT_ALLOWLIST[0].key;
    expect(resolveFont(first)).toBe(FONT_ALLOWLIST[0].stack);
    expect(resolveFont("../../evil")).toBe(FONT_ALLOWLIST[0].stack); // never echoes arbitrary input
  });

  test("themeVars ignores non-colour primary values (no injection via token)", () => {
    const vars = themeVars({ mode: "light", primary: "url(evil)" as string });
    expect(vars["--saiku-app-primary"]).toBeUndefined();
  });
});
```

- [ ] **Step 2: Run — expect failure.** `npx vitest run src/lib/dashboard/appTheme.test.ts` → FAIL.

- [ ] **Step 3: Implement `appTheme.ts`**

```ts
import type { AppTheme } from "$lib/api/apps";

export const FONT_ALLOWLIST = [
  { key: "sans-1", label: "System sans", stack: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif" },
  { key: "serif-1", label: "Editorial serif", stack: "Georgia, 'Times New Roman', serif" },
  { key: "mono-1", label: "Monospace", stack: "ui-monospace, SFMono-Regular, Menlo, monospace" },
] as const;

export function resolveFont(key: string | undefined): string {
  const hit = FONT_ALLOWLIST.find((f) => f.key === key);
  return (hit ?? FONT_ALLOWLIST[0]).stack;
}

/** A strict colour token: #rgb/#rrggbb/#rrggbbaa only. Anything else is rejected (no url(), no expressions). */
const COLOUR = /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
function colour(v: string | undefined): string | undefined {
  return v && COLOUR.test(v) ? v : undefined;
}

export function themeVars(theme: AppTheme): Record<string, string> {
  const out: Record<string, string> = {};
  const set = (k: string, v: string | undefined) => {
    if (v !== undefined) out[k] = v;
  };
  set("--saiku-app-primary", colour(theme.primary));
  set("--saiku-app-accent", colour(theme.accent));
  set("--saiku-app-bg", colour(theme.bg));
  set("--saiku-app-fg", colour(theme.fg));
  set("--saiku-app-font", resolveFont(theme.font));
  return out;
}
```

- [ ] **Step 4: Run — expect pass.** 3 tests.

- [ ] **Step 5: Commit**

```bash
git add saiku-ui/src/lib/dashboard/appTheme.ts saiku-ui/src/lib/dashboard/appTheme.test.ts
git commit -m "feat(app-builder): theme-token → CSS-var mapping + font allowlist"
```

---

## Task 3: Scoped + sanitised custom CSS (SECURITY-CRITICAL)

This is the one Phase-1 feature that widens the attack surface. It must (a) scope every author selector under the app root, and (b) reject hostile constructs (`@import`, `@font-face`, remote `url()`, `position:fixed`, `expression()`, `behavior`, `-moz-binding`), failing closed (drop all CSS if it can't be fully parsed/scoped).

**Files:**
- Create: `saiku-ui/src/lib/dashboard/cssSanitiser.ts`
- Create: `saiku-ui/src/lib/dashboard/cssSanitiser.test.ts`
- Modify: `saiku-ui/package.json` (add a CSS parser dep — see Step 0)

- [ ] **Step 0: Add a real CSS parser** (do NOT hand-roll a tokenizer). Use `csstree` (`css-tree`), a mature, dependency-light CSS parser.

**FIRST run the mandatory dependency check** (per repo policy): invoke the `sonatype-guide` skill on `css-tree` (latest) before adding. Only proceed if it's clean/non-malicious.

Run: `cd saiku-ui && npm install css-tree && npm install -D @types/css-tree`
Expected: added to `package.json`/`package-lock.json`.

- [ ] **Step 1: Write the failing test** — `cssSanitiser.test.ts`

```ts
import { describe, expect, test } from "vitest";
import { sanitiseAndScopeCss } from "./cssSanitiser";

const ROOT = '[data-saiku-app="app1"]';

describe("sanitiseAndScopeCss", () => {
  test("scopes every selector under the app root", () => {
    const out = sanitiseAndScopeCss(".card { color: red } h1 { margin: 0 }", ROOT);
    expect(out).toContain(`${ROOT} .card`);
    expect(out).toContain(`${ROOT} h1`);
    expect(out).not.toMatch(/^\s*\.card/m); // no unscoped selector survives
  });

  test("drops @import entirely", () => {
    const out = sanitiseAndScopeCss('@import url("//evil");\n.a{color:red}', ROOT);
    expect(out).not.toContain("@import");
    expect(out).toContain(`${ROOT} .a`);
  });

  test("strips remote url() but keeps data: and same-origin refs", () => {
    const out = sanitiseAndScopeCss('.a{background:url(https://evil/x.png)} .b{background:url(data:image/png;base64,AA)}', ROOT);
    expect(out).not.toContain("evil");
    expect(out).toContain("data:image/png");
  });

  test("removes position:fixed, expression(), behavior, -moz-binding", () => {
    const css = '.a{position:fixed} .b{width:expression(alert(1))} .c{behavior:url(x.htc)} .d{-moz-binding:url(x)}';
    const out = sanitiseAndScopeCss(css, ROOT);
    expect(out).not.toMatch(/position\s*:\s*fixed/i);
    expect(out).not.toMatch(/expression\s*\(/i);
    expect(out).not.toMatch(/behavior\s*:/i);
    expect(out).not.toMatch(/-moz-binding/i);
  });

  test("fails closed: unparseable CSS yields empty string", () => {
    expect(sanitiseAndScopeCss("this is { not ; valid ) css {{{", ROOT)).toBe("");
  });

  test("empty / nullish input yields empty string", () => {
    expect(sanitiseAndScopeCss("", ROOT)).toBe("");
    expect(sanitiseAndScopeCss(undefined as unknown as string, ROOT)).toBe("");
  });
});
```

- [ ] **Step 2: Run — expect failure.** FAIL — module missing.

- [ ] **Step 3: Implement `cssSanitiser.ts`** using csstree's AST — walk, prune hostile nodes, prefix selectors, serialise. Fail closed on parse error.

```ts
import * as csstree from "css-tree";

const FORBIDDEN_DECL = /^(behavior|-moz-binding)$/i;
// property values we reject outright
function valueIsHostile(prop: string, value: string): boolean {
  const v = value.toLowerCase();
  if (/expression\s*\(/.test(v)) return true;
  if (prop.toLowerCase() === "position" && /\bfixed\b/.test(v)) return true;
  return false;
}

/** Allow only data: and relative/same-origin url() targets; reject remote schemes. */
function urlIsAllowed(raw: string): boolean {
  const u = raw.trim().replace(/^['"]|['"]$/g, "");
  if (u.startsWith("data:")) return true;
  if (/^[a-z]+:\/\//i.test(u)) return false; // http(s)://, //host, etc.
  if (u.startsWith("//")) return false;
  return true; // relative / same-origin asset ref
}

/**
 * Sanitise author CSS and scope every rule under `rootSelector`. Returns "" if the
 * CSS cannot be fully parsed (fail-closed). Never throws.
 */
export function sanitiseAndScopeCss(css: string | undefined, rootSelector: string): string {
  if (!css || !css.trim()) return "";
  let ast: csstree.CssNode;
  try {
    ast = csstree.parse(css, { onParseError: (e) => { throw e; } });
  } catch {
    return ""; // fail closed
  }

  try {
    csstree.walk(ast, {
      visit: "Atrule",
      enter(node, item, list) {
        const name = node.name.toLowerCase();
        if (name === "import" || name === "font-face" || name === "charset" || name === "namespace") {
          list.remove(item); // drop hostile / pointless at-rules
        }
      },
    });

    // Drop hostile declarations.
    csstree.walk(ast, {
      visit: "Declaration",
      enter(node, item, list) {
        const prop = node.property;
        if (FORBIDDEN_DECL.test(prop)) return void list.remove(item);
        const value = csstree.generate(node.value);
        if (valueIsHostile(prop, value)) return void list.remove(item);
      },
    });

    // Reject remote url() references.
    csstree.walk(ast, {
      visit: "Url",
      enter(node) {
        const raw = (node as csstree.Url).value;
        if (!urlIsAllowed(raw)) {
          // Blank the url so the declaration is harmless; leave structure valid.
          (node as csstree.Url).value = "";
        }
      },
    });

    // Scope every top-level rule's selectors under the app root.
    csstree.walk(ast, {
      visit: "Selector",
      enter(node) {
        node.children.prependData({ type: "Combinator", name: " " });
        node.children.prependData({ type: "Raw", value: rootSelector });
      },
    });

    return csstree.generate(ast);
  } catch {
    return ""; // any transform failure → fail closed
  }
}
```

> Note for the implementer: the exact csstree node-manipulation API (`prependData`, `Raw`/`Combinator` node shapes) may need small adjustment to the installed csstree version — verify against the tests, which are the contract. The invariants (scoped, no `@import`, no remote url, no fixed/expression/behavior/binding, fail-closed) are non-negotiable.

- [ ] **Step 4: Run — expect pass.** All sanitiser tests green.

- [ ] **Step 5: Run the full check + commit**

```bash
cd saiku-ui && npm run check
git add saiku-ui/src/lib/dashboard/cssSanitiser.ts saiku-ui/src/lib/dashboard/cssSanitiser.test.ts saiku-ui/package.json saiku-ui/package-lock.json
git commit -m "feat(app-builder): scoped + fail-closed custom-CSS sanitiser (security)"
```

- [ ] **Step 6: Add a Hegel property test (defence in depth)** — mirror the sanitiser invariants over generated inputs.

Create `saiku-proptest/src/test/java/org/saiku/proptest/...`? — NO: this logic is TypeScript. Instead add a **fast-check** property test in the UI (fast-check is the JS PBT lib). Create `saiku-ui/src/lib/dashboard/cssSanitiser.property.test.ts`:

```ts
import { describe, test, expect } from "vitest";
import fc from "fast-check";
import { sanitiseAndScopeCss } from "./cssSanitiser";

describe("cssSanitiser properties", () => {
  test("output never contains @import, expression(, behavior:, -moz-binding, or position:fixed", () => {
    fc.assert(
      fc.property(fc.string(), (s) => {
        const out = sanitiseAndScopeCss(s + " .x{color:red}", '[data-saiku-app="a"]').toLowerCase();
        return (
          !out.includes("@import") &&
          !out.includes("expression(") &&
          !out.includes("behavior:") &&
          !out.includes("-moz-binding") &&
          !/position\s*:\s*fixed/.test(out)
        );
      }),
    );
  });
});
```

Run the sonatype-guide check on `fast-check`, then `npm install -D fast-check`, run `npx vitest run src/lib/dashboard/cssSanitiser.property.test.ts`, commit.

---

## Task 4: `AppResource` — backend CRUD (opaque JSON)

**Files:**
- Create: `saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/apps/AppResource.java`
- Create: `saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/apps/AppResourceTest.java`
- Modify: the JAX-RS registration (mirror where `DashboardResource` is registered — `spring-rest.xml` / the Jersey `packages` scan).

Mirror `DashboardResource` exactly: opaque `JsonNode` CRUD, `.saikuapp` files in the JCR repo, reuse history/comment services if they're doc-type-agnostic. Do NOT introduce a typed Java model — the UI is the schema authority (same posture as dashboards).

- [ ] **Step 1: Read `DashboardResource.java` fully.** The plan mirrors it; do not diverge from its security/validation posture (path validation, auth, size caps).

- [ ] **Step 2: Write the failing test** — `AppResourceTest.java`. Mirror `DashboardResourceTest` (if it exists; else model on another resource test): assert save→get round-trips the opaque JSON, list includes the saved app, delete removes it, and a path-traversal name is rejected.

- [ ] **Step 3: Run — expect failure** (`AppResource` missing).

Run: `mvn -pl saiku-core/saiku-web -am test -Dtest=AppResourceTest`
Expected: compile error / no such class.

- [ ] **Step 4: Implement `AppResource`** at `@Path("/saiku/api/apps")` mirroring `DashboardResource`: `GET /apps` (list), `GET /apps/{id}`, `POST /apps` (save opaque `JsonNode`), `PUT /apps/{id}`, `DELETE /apps/{id}`. Store under a `.saikuapp` extension in the same repo area dashboards use. Reuse `DashboardResource`'s path-safety + auth guards verbatim.

- [ ] **Step 5: Run — expect pass.** Then `mvn -pl saiku-core/saiku-web spotless:apply`.

- [ ] **Step 6: Commit**

```bash
git add saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/apps/ saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/apps/
git commit -m "feat(app-builder): AppResource CRUD for .saikuapp (opaque JSON, mirrors DashboardResource)"
```

---

## Task 5: UI app store + API client

**Files:**
- Create: `saiku-ui/src/lib/stores/appDoc.svelte.ts`
- Create: `saiku-ui/src/lib/stores/appDoc.svelte.test.ts`
- Modify: `saiku-ui/src/lib/api/apps.ts` (add `listApps/getApp/saveApp/deleteApp` fetch helpers, mirroring `dashboards.ts`)

Store responsibilities: `current: SaikuApp | null`, `activePageId`, load/save (via the API client), page CRUD (add / rename / reorder / delete, guarding against deleting the last page), `setNav`, `setTheme`, undo/redo (reuse `history.ts` pattern from the dashboard store). All updates immutable (repo rule).

- [ ] **Step 1: Write failing unit tests** for: add/rename/reorder/delete page (immutably), cannot delete last page, `setActivePage`, `setNav`/`setTheme` produce new objects. (Network stubbed.)
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement the store** (Svelte 5 runes class, mirroring `ossieQuery.svelte.ts` / the dashboard store structure). Respect the effect-discipline note in CLAUDE.md.
- [ ] **Step 4: Run — PASS.**
- [ ] **Step 5: Commit** `feat(app-builder): app document store (pages CRUD, nav/theme, undo)`

---

## Task 6: `AppShell` — header + configurable nav + page region

**Files:**
- Create: `saiku-ui/src/lib/views/app/AppShell.svelte`
- Create: `saiku-ui/src/lib/views/app/AppNavRail.svelte`
- Create: `saiku-ui/src/lib/views/app/AppTopNav.svelte`
- Create: `saiku-ui/src/lib/views/app/AppHeader.svelte`

Component spec (implement to this structure; markup fills the described layout, styles via the theme vars from Task 2):

- **`AppShell.svelte`** — props `{ app: SaikuApp, editable: boolean }`. Renders a root element `<div data-saiku-app={app.id} style={themeVarsInline}>`; injects a `<style>` block with `sanitiseAndScopeCss(app.theme.customCss, '[data-saiku-app="…"]')` (Task 3) and `document.adoptedStyleSheets` or a scoped `<svelte:element this="style">`; lays out: `AppHeader` (top), then either `AppNavRail` (left) or `AppTopNav` (under header) based on `app.nav.position`, then the **page region** rendering the active page (Task 7), then a **reserved right slot** (empty; `assistantSlot.enabled` gates a placeholder in edit mode only). Responsive: below 768px the rail becomes a bottom bar (reuse the `responsiveLayout.ts` breakpoint constant).
- **`AppNavRail` / `AppTopNav`** — props `{ pages, activeId, onSelect, editable }`. Render one nav item per page (icon + title), highlight active, emit `onSelect(pageId)`. In `editable` mode, add/reorder/rename affordances (can defer reorder-drag to a follow-up step; rename inline).
- **`AppHeader`** — logo (or app name), app name, global controls slot (theme/mode toggle, share/embed/present buttons wired in Task 9/10).

- [ ] **Step 1:** Build `AppHeader`, `AppNavRail`, `AppTopNav` (pure presentational; props in, events out).
- [ ] **Step 2:** Build `AppShell` wiring nav position, theme vars, sanitised custom-CSS injection, and the page region placeholder.
- [ ] **Step 3:** `npm run check` (0 errors) + `npm run lint`.
- [ ] **Step 4: Commit** `feat(app-builder): AppShell + configurable rail/top nav + header`

---

## Task 7: Render a page via the existing dashboard grid + per-page state

**Files:**
- Create: `saiku-ui/src/lib/views/app/AppPageView.svelte`
- Modify: `saiku-ui/src/lib/dashboard/urlFilterState.ts` (add a page dimension to the URL state)

- **`AppPageView.svelte`** — props `{ page: AppPage, editable }`. Renders `page.grid` through the **existing** dashboard grid/editor components (reuse `DashboardEditor`/`DashboardGrid` pointed at `page.grid`; do not fork them). Each page keeps its own filter state; the active page id + that page's active filters mirror to the URL (`?p=<pageId>&f=…`).

- [ ] **Step 1:** Write a test asserting a 1-page app's `AppPageView` renders the identical tile set as the equivalent standalone dashboard (parity). (Component test or a logic-level assertion that the grid object is passed through unchanged.)
- [ ] **Step 2:** Implement `AppPageView` reusing the grid renderer.
- [ ] **Step 3:** Extend `urlFilterState.ts` to namespace filter state by page id; add a test that switching pages preserves each page's filters and the URL round-trips.
- [ ] **Step 4:** `npm run check`; **Commit** `feat(app-builder): page rendering via existing grid + per-page URL/filter state`

---

## Task 8: Import-from-dashboard + Apps section in the repo browser

**Files:**
- Modify: the repo/catalogue browser (mirror how dashboards are listed) to add an **Apps** section: list `.saikuapp`, "New app", "New app from dashboard…" (opens a dashboard picker → `appFromDashboard(name, layout)` → save).
- Modify: routing so an app opens in the `AppShell` (edit + view modes), analogous to how a dashboard opens in `DashboardEditor`.

- [ ] **Step 1:** Add the Apps catalogue section + create/open actions.
- [ ] **Step 2:** Wire "New app from dashboard" (import copies the layout into `pages[0].grid`).
- [ ] **Step 3:** `npm run check`; **Commit** `feat(app-builder): Apps repo section + new-app + import-from-dashboard`

---

## Task 9: Save/load end-to-end + presentation/view mode

- [ ] **Step 1:** Wire the store's load/save to `AppResource` (Task 4). Save button persists; open loads + `normaliseApp`.
- [ ] **Step 2:** View/present mode = `AppShell` with `editable=false` (no page-edit affordances; nav + pages only).
- [ ] **Step 3:** Manual smoke via the launcher (per CLAUDE.md: `mvn -pl saiku-webapp,saiku-launcher clean` then rebuild + run) — create a 2-page branded app, save, reload, confirm it round-trips. **Commit** `feat(app-builder): app save/load + view mode wired end-to-end`

---

## Task 10: Embedding an app as one unit (`kind="app"`)

**Files:**
- Modify: `saiku-ui/src/embed/saiku-embed.ts` + `SaikuEmbed.svelte` (add `kind="app"` → render `AppShell` read-only)
- Modify: `saiku-core/.../embed/EmbedViewResource.java` if a new resource grant type is needed (mirror the dashboard embed path; RLS/PII ride the token and apply per-tile-query exactly as today — the app wrapper adds no new query path).
- Modify: `saiku-ui/src/embed/README.md` (document `kind="app"`, note custom CSS is designer-only, not embed-configurable in Phase 1).

- [ ] **Step 1:** Extend the embed component with `kind="app"` rendering `AppShell` (editable=false), token-scoped to the whole app.
- [ ] **Step 2:** Verify RLS/PII still fail-closed per tile (reuse existing embed tests; add an app-embed case).
- [ ] **Step 3:** `npm run check`; docs; **Commit** `feat(app-builder): embed an app as one token-scoped unit (kind=app)`

---

## Task 11: Back-compat, parity, and CI floors

- [ ] **Step 1:** Assert existing `.saikudash` dashboards still open unchanged in the dashboard editor (unaffected code path) — add/confirm a regression test.
- [ ] **Step 2:** Parity test: a 1-page app whose page grid == a dashboard renders the same tiles/filters (from Task 1/7) — confirm green.
- [ ] **Step 3:** Run the whole gate: `cd saiku-ui && npm run check && npm test && npm run lint`; `mvn -pl saiku-core/saiku-web -am test`. Bump any surefire floor in `.github/test-floors.json` for the new `AppResourceTest`.
- [ ] **Step 4: Final commit + PR** to `development`. PR body: summary, screenshots of a 2-page branded app, the security note on custom CSS, and a link to the design spec.

---

## Deferred to later phases (explicitly NOT in this plan)
Custom-tile/plugin framework; input/button/form widgets + action/event model; write-back; the AI-assistant panel wiring (the shell reserves its slot only); advanced-analytics tiles (what-if/forecast/anomaly as first-class). Each is its own spec → plan.

## Test strategy summary
- **Unit (Vitest):** model round-trip/parity, theme mapping, store page-CRUD, per-page URL state.
- **Property (fast-check):** the CSS sanitiser invariants over arbitrary input (fail-closed, never emits a forbidden token).
- **Backend (surefire):** `AppResource` CRUD + path-safety.
- **Manual smoke:** build a real 2-page branded app in the launcher and round-trip it.
- **Regression:** existing dashboards unaffected; parity of 1-page app == dashboard.
</content>

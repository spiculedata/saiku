import { expect, test, type Page, type Route } from "@playwright/test";

/**
 * End-to-end smoke test for the App Builder (Phase 1, Task 9) with a mocked
 * backend. The real point of this spec is to mount a multi-page app in a REAL
 * browser and prove that AppPageView's two per-page Svelte 5 `$effect`s (hydrate
 * + write-back) do NOT re-enter — i.e. never trip Svelte's
 * `effect_update_depth_exceeded`, which (per CLAUDE.md) tears down a component's
 * whole reactivity graph and silently makes every event handler inert.
 *
 * The loop-safety is meant to hold BY CONSTRUCTION (the hydrate effect
 * short-circuits on an unchanged page id under `untrack`; the write-back effect
 * guards the hydrate-caused store change). This test is the only thing that
 * exercises it in a browser instead of jsdom.
 *
 * Flow, all against mocked persistence (no Java backend):
 *   1. /apps catalogue → "+ New app" → Create → POST persists the doc → client
 *      navigation to /apps/<path> → AppEditor loads it via GET → AppShell mounts.
 *   2. In edit mode: add a second page, then switch between the two pages
 *      several times via the nav rail.
 *   3. Assert: NO `effect_update_depth_exceeded` ever hit the console/pageerror
 *      channel, the nav actually changes the active page each time (proving the
 *      reactivity graph is intact), and the Save / Add-page controls stay live.
 *
 * Mirrors ossie-workbench.spec.ts: non-live tier, boots vite preview on :4173,
 * intercepts every `/rest/**` call with route handlers.
 */

interface AppBackendHandle {
  savedPaths(): string[];
  getSaved(path: string): string | null;
}

const SESSION_BODY = JSON.stringify({
  username: "admin",
  roles: ["ROLE_ADMIN", "ROLE_USER"],
  sessionid: "e2e-session-app",
  language: "en",
  isadmin: true,
});

/**
 * Intercept every backend call the App Builder makes and answer with canned
 * JSON. Apps CRUD is persisted in-memory so New-app → save → open round-trips.
 *
 * Registration order matters: Playwright checks handlers most-recent-first, so
 * the broad `/rest/**` safety net is registered FIRST and the specific routes
 * (registered after) take precedence over it.
 */
function registerAppBackend(page: Page): AppBackendHandle {
  const saved = new Map<string, string>();

  // Safety net: anything under /rest/** we don't explicitly mock returns empty
  // JSON so boot-time probes (info/capabilities, admin/version, stats ping)
  // never leak the SPA fallback HTML back into a fetch that expects JSON.
  void page.route("**/rest/**", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
  });

  // Session: a signed-in admin so the /apps route renders the catalogue/editor
  // rather than the LoginForm.
  void page.route("**/rest/saiku/session", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: SESSION_BODY });
    } else {
      await route.fulfill({ status: 204 });
    }
  });

  // Repository listing — the NewAppModal's folder picker (RepositoryBrowser)
  // lists this on mount. Empty tree is fine; the default folder (homes/admin)
  // is composed from the mocked session username, not from a picked node.
  void page.route("**/rest/saiku/api/repository**", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  // Apps CRUD + list. `/api/apps` (no trailing path) is the catalogue list;
  // `/api/apps/<repo/path>` is create (POST) / load (GET) / delete.
  void page.route("**/rest/saiku/api/apps**", async (route: Route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    const match = url.pathname.match(/\/api\/apps\/?(.*)$/);
    const rel = decodeURIComponent(match?.[1] ?? "");

    if (!rel) {
      const list = Array.from(saved.keys()).map((p) => ({ path: p, name: p }));
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(list) });
      return;
    }

    if (method === "POST") {
      saved.set(rel, route.request().postData() ?? "");
      await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
    } else if (method === "GET") {
      const body = saved.get(rel);
      if (body) {
        await route.fulfill({ status: 200, contentType: "application/json", body });
      } else {
        await route.fulfill({
          status: 404,
          contentType: "application/json",
          body: JSON.stringify({ error: `not found: ${rel}` }),
        });
      }
    } else if (method === "DELETE") {
      saved.delete(rel);
      await route.fulfill({ status: 200, contentType: "text/plain", body: "" });
    } else {
      await route.fulfill({ status: 405, contentType: "text/plain", body: "" });
    }
  });

  return {
    savedPaths: () => Array.from(saved.keys()),
    getSaved: (path: string) => saved.get(path) ?? null,
  };
}

test.describe("App Builder (mocked backend)", () => {
  test("multi-page app: nav switching stays live and never trips effect_update_depth_exceeded", async ({
    page,
  }) => {
    // ----------------------------------------------------------------
    // Collect every console message + uncaught page error. The single
    // must-not-appear string is Svelte's effect re-entrancy bail-out.
    // ----------------------------------------------------------------
    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "error") consoleErrors.push(msg.text());
    });
    page.on("pageerror", (err) => {
      pageErrors.push(err.message);
    });

    const backend = registerAppBackend(page);

    // ----------------------------------------------------------------
    // 1. /apps catalogue → create a new app.
    // ----------------------------------------------------------------
    await page.goto("/apps");
    await page.waitForLoadState("networkidle");

    // Header "+ New app" (the EmptyState action carries the same label, so pin
    // to the first match).
    await page.getByRole("button", { name: "+ New app", exact: true }).first().click();

    // NewAppModal: name defaults to "Untitled app", folder to homes/admin — both
    // pre-filled, so Create is enabled immediately. Scope to the modal footer —
    // the folder picker (RepositoryBrowser) carries its own "Create folder"
    // button, so an unscoped name match is ambiguous.
    const createBtn = page.locator("footer").getByRole("button", { name: "Create", exact: true });
    await expect(createBtn).toBeEnabled();
    await createBtn.click();

    // AppEditor loads the persisted doc and AppShell mounts. The default app has
    // one page titled "Overview" on a rail nav in edit mode.
    const overviewNav = page
      .locator(".saiku-app__rail-item")
      .filter({ hasText: "Overview" });
    await expect(overviewNav).toBeVisible();
    await expect(overviewNav).toHaveAttribute("aria-current", "page");

    // Persistence round-tripped through the mocked POST/GET.
    expect(backend.savedPaths().length).toBe(1);

    // ----------------------------------------------------------------
    // 2. Add a second page (goes active), then switch back and forth.
    // ----------------------------------------------------------------
    await page.getByRole("button", { name: "Add page", exact: true }).click();

    const page2Nav = page.locator(".saiku-app__rail-item").filter({ hasText: "Page 2" });
    await expect(page2Nav).toBeVisible();
    // Freshly-added page is the active one.
    await expect(page2Nav).toHaveAttribute("aria-current", "page");

    // Switch several times. Each click must actually move the active state — if
    // the reactivity graph had torn down (effect re-entrancy), the click handler
    // would be inert and aria-current would stay put.
    const switches: Array<["Overview" | "Page 2"]> = [
      ["Overview"],
      ["Page 2"],
      ["Overview"],
      ["Page 2"],
      ["Overview"],
    ];
    for (const [target] of switches) {
      const targetNav = target === "Overview" ? overviewNav : page2Nav;
      const otherNav = target === "Overview" ? page2Nav : overviewNav;
      await targetNav.click();
      await expect(targetNav).toHaveAttribute("aria-current", "page");
      await expect(otherNav).not.toHaveAttribute("aria-current", "page");
    }

    // ----------------------------------------------------------------
    // 3. The interactive controls are still live after all the switching.
    // Add a THIRD page via the still-working add button, and confirm the Save
    // control is clickable (it would be inert if the graph had collapsed).
    // ----------------------------------------------------------------
    await page.getByRole("button", { name: "Add page", exact: true }).click();
    await expect(page.locator(".saiku-app__rail-item").filter({ hasText: "Page 3" })).toBeVisible();

    const saveBtn = page.getByRole("button", { name: "Save", exact: true });
    await expect(saveBtn).toBeEnabled();
    await saveBtn.click();
    // Save toast fires — proves the click handler ran end-to-end.
    await expect(page.getByText("Saved", { exact: true })).toBeVisible();

    // ----------------------------------------------------------------
    // THE ASSERTION THIS TASK EXISTS FOR: no effect re-entrancy anywhere.
    // ----------------------------------------------------------------
    const allDiagnostics = [...consoleErrors, ...pageErrors];
    const reentrancy = allDiagnostics.filter((m) => m.includes("effect_update_depth_exceeded"));
    expect(
      reentrancy,
      `effect_update_depth_exceeded must never fire. Captured diagnostics:\n${allDiagnostics.join("\n")}`,
    ).toHaveLength(0);
  });
});

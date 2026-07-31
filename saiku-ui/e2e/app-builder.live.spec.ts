import { expect, test } from "@playwright/test";

/**
 * Live-backend counterpart to the mocked `app-builder.spec.ts`. Drives the App
 * Builder "empty → built out → persisted" flow against a REAL Saiku launcher on
 * port 8080 (the SvelteKit UI served from the fat-JAR, real session + real
 * `/api/apps` persistence). Gated on `RUN_LIVE_E2E=1` (see playwright.config.ts)
 * so CI never runs it unintentionally.
 *
 * Requires the launcher to be running with the CURRENT UI bundle:
 *
 *   npm run build              # in saiku-ui/  (bakes this branch's UI into dist/)
 *   mvn -pl saiku-webapp,saiku-launcher clean package -DskipTests
 *   java -jar saiku-launcher/target/saiku-*.jar serve --port 8080
 *   RUN_LIVE_E2E=1 npm run e2e -- app-builder.live.spec.ts
 *
 * Scope note: the empty→add-tile→save→reload→persist→view round-trip is what
 * the browser tier drives robustly. Binding a tile to a real cube and asserting
 * real FoodMart cells is covered end-to-end at the backend contract layer by
 * `AppBuilderIT` (saiku-launcher ITs) — that path runs the tile's inline query
 * through the same executor and asserts real Store-Sales values, so this spec
 * deliberately does NOT re-drive the in-modal query builder.
 */

const LIVE_USER = process.env.LIVE_ADMIN_USER ?? "admin";
const LIVE_PASS = process.env.LIVE_ADMIN_PASS ?? "admin";
// The launcher serves the SvelteKit UI under a base path (built with
// SAIKU_BASE_PATH=/ui) — `:8080/` itself is not the app, so every route is
// prefixed. Overridable for a same-origin dev server mounted at root.
const BASE = process.env.LIVE_UI_BASE ?? "/ui";

test.describe("App Builder (live backend)", () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem("saiku.tour.done", "1");
      window.localStorage.setItem(`saiku.tour.done.${"admin"}`, "1");
    });
    await page.goto(`${BASE}/`);
    await page.waitForLoadState("networkidle");
    // Log in if the page is showing the login form. LoginForm.svelte's inputs
    // carry no `name` attr (bound values + autocomplete), so target by label;
    // the submit button is "Sign in" exactly (a separate "Sign in as demo user"
    // button also exists, so an inexact match is ambiguous).
    const userField = page.getByLabel("Username");
    if (await userField.isVisible().catch(() => false)) {
      await userField.fill(LIVE_USER);
      await page.getByLabel("Password").fill(LIVE_PASS);
      await page.getByRole("button", { name: "Sign in", exact: true }).click();
      await page.waitForLoadState("networkidle");
      // Land somewhere authenticated before the test navigates on.
      await expect(page.getByLabel("Username")).toHaveCount(0);
    }
  });

  test("empty → add a tile → save → reload → tile persists and renders", async ({ page }) => {
    // Uncaught Svelte effect re-entrancy would silently deaden the editor's
    // controls — assert it never fires across the whole flow.
    const diagnostics: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "error") diagnostics.push(msg.text());
    });
    page.on("pageerror", (err) => diagnostics.push(err.message));

    // ----------------------------------------------------------------
    // 1. Create a fresh app — unique name so reruns against a persistent
    //    launcher home don't collide.
    // ----------------------------------------------------------------
    const appName = `E2E App ${Date.now()}`;
    await page.goto(`${BASE}/apps`);
    await page.waitForLoadState("networkidle");

    await page.getByRole("button", { name: "+ New app", exact: true }).first().click();

    // NewAppModal: set a unique name, then Create (scoped to the modal footer —
    // the folder picker carries its own buttons).
    const nameField = page.getByLabel(/name/i).first();
    if (await nameField.isVisible().catch(() => false)) {
      await nameField.fill(appName);
    }
    await page.locator("footer").getByRole("button", { name: "Create", exact: true }).click();

    // AppShell mounts in edit mode with a default "Overview" page.
    await expect(page.locator(".saiku-app__rail-item").filter({ hasText: "Overview" })).toBeVisible();

    // Empty page: no tiles yet.
    await expect(page.locator(".tile")).toHaveCount(0);

    // ----------------------------------------------------------------
    // 2. Add a table tile via the toolbar.
    // ----------------------------------------------------------------
    await page.getByRole("button", { name: /Add tile/ }).click();
    await page.getByRole("menuitem", { name: /Table/ }).click();
    await expect(page.locator(".tile")).toHaveCount(1);
    // The tile's ⚙ (cube-binding entry point) is reachable in edit mode.
    await expect(page.getByRole("button", { name: "Edit tile" })).toBeVisible();

    // ----------------------------------------------------------------
    // 3. Save through the real /api/apps POST, then reload and reopen.
    // ----------------------------------------------------------------
    const savePost = page.waitForResponse(
      (r) => /\/rest\/saiku\/api\/apps\//.test(r.url()) && r.request().method() === "POST" && r.ok(),
    );
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await savePost;
    await expect(page.getByText("Saved", { exact: true })).toBeVisible();

    // Full page reload — the editor must rehydrate the persisted doc from the
    // backend (not from any in-memory state).
    await page.reload();
    await page.waitForLoadState("networkidle");

    // ----------------------------------------------------------------
    // 4. The tile survived the round-trip.
    // ----------------------------------------------------------------
    await expect(page.locator(".saiku-app__rail-item").filter({ hasText: "Overview" })).toBeVisible();
    await expect(page.locator(".tile")).toHaveCount(1);

    // ----------------------------------------------------------------
    // 5. View mode renders the built-out app read-only (tile shell present,
    //    add-tile toolbar gone). Data cells depend on the tile's binding and
    //    are asserted with real values by AppBuilderIT, not here.
    // ----------------------------------------------------------------
    await page.getByRole("button", { name: "View", exact: true }).click();
    await expect(page.locator(".tile")).toHaveCount(1);
    await expect(page.getByRole("button", { name: /Add tile/ })).toHaveCount(0);

    expect(
      diagnostics.filter((m) => m.includes("effect_update_depth_exceeded")),
      `effect re-entrancy must never fire. Diagnostics:\n${diagnostics.join("\n")}`,
    ).toHaveLength(0);
  });
});

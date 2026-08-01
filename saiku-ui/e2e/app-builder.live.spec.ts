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
 *
 * Phase-2 (custom + plugin tiles) scope — see the second test below:
 *  - The custom add-tile affordance IS browser-drivable: "+ Add tile" → "Custom…"
 *    seeds a `type:"custom"` tile, which we add, save, reload and assert persists.
 *  - A `plugin` tile's sandboxed iframe render is asserted against the live app,
 *    but the tile is AUTHORED through the real `/rest/saiku/api/apps` contract
 *    (the same one `AppBuilderIT` proves), NOT by clicking a renderer picker:
 *    the current editor has no control that sets `tile.custom.renderer`, so
 *    "Custom…" only ever yields a rendererless custom tile and the ⚙ editor's
 *    plugin-picker (`<span>Plugin</span>` + `<select bind:value={selectedPluginId}>`)
 *    is never reachable by clicks alone. Authoring via REST + asserting the real
 *    rendered iframe keeps the coverage honest without inventing a UI flow that
 *    does not exist on this build.
 *  - The plugin iframe only mounts when the `records-bars` plugin is installed on
 *    the launcher (staged in demo mode, or dropped into
 *    `saiku-home/tile-plugins/records-bars/` manually). Run the live launcher with
 *    `SAIKU_DEMO=true` so the seed plugin is present, exactly as `AppBuilderIT`
 *    stages it into its harness home.
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

  test("custom tile via toolbar persists; a plugin tile renders its sandboxed iframe", async ({ page }) => {
    const diagnostics: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "error") diagnostics.push(msg.text());
    });
    page.on("pageerror", (err) => diagnostics.push(err.message));

    // ----------------------------------------------------------------
    // 1. Create a fresh app and land in the editor (as test 1).
    // ----------------------------------------------------------------
    const appName = `E2E Custom App ${Date.now()}`;
    await page.goto(`${BASE}/apps`);
    await page.waitForLoadState("networkidle");
    await page.getByRole("button", { name: "+ New app", exact: true }).first().click();
    const nameField = page.getByLabel(/name/i).first();
    if (await nameField.isVisible().catch(() => false)) {
      await nameField.fill(appName);
    }
    await page.locator("footer").getByRole("button", { name: "Create", exact: true }).click();
    await expect(page.locator(".saiku-app__rail-item").filter({ hasText: "Overview" })).toBeVisible();

    // ----------------------------------------------------------------
    // 2. Add a CUSTOM tile through the real toolbar menu. The "Custom…" entry
    //    is enabled once the builtin renderers register at /apps boot (import
    //    side-effect in the route), so this is a genuine click path — it seeds a
    //    `type:"custom"` tile. (The renderer itself is NOT choosable in the ⚙
    //    editor on this build — see the file header — so we do not attempt to
    //    turn it into a plugin tile by clicking; that is authored via REST below.)
    // ----------------------------------------------------------------
    await expect(page.locator(".tile")).toHaveCount(0);
    await page.getByRole("button", { name: /Add tile/ }).click();
    await page.getByRole("menuitem", { name: /Custom/ }).click();
    await expect(page.locator(".tile")).toHaveCount(1);
    // Its ⚙ edit affordance is reachable in edit mode.
    await expect(page.getByRole("button", { name: "Edit tile" })).toBeVisible();

    // Save + reload → the custom tile survives the real /api/apps round-trip.
    const savePost = page.waitForResponse(
      (r) => /\/rest\/saiku\/api\/apps\//.test(r.url()) && r.request().method() === "POST" && r.ok(),
    );
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await savePost;
    await expect(page.getByText("Saved", { exact: true })).toBeVisible();
    await page.reload();
    await page.waitForLoadState("networkidle");
    await expect(page.locator(".tile")).toHaveCount(1);

    // ----------------------------------------------------------------
    // 3. Author a PLUGIN tile through the SAME /rest/saiku/api/apps contract the
    //    Save button uses (AppBuilderIT proves this contract end-to-end). The
    //    REST path is derived from the app's own URL so it targets exactly the
    //    app this browser session created; the XSRF cookie is echoed as the
    //    header, mirroring the app's http.ts interceptor for state-changing
    //    /rest/** calls. This is the honest way to reach a plugin tile given the
    //    editor exposes no renderer picker.
    // ----------------------------------------------------------------
    const pathname = new URL(page.url()).pathname;
    const restAppUrl = "/rest/saiku/api" + pathname.slice(pathname.indexOf("/apps/"));

    const cookies = await page.context().cookies();
    const xsrf = cookies.find((c) => c.name === "XSRF-TOKEN")?.value ?? "";

    const pluginDoc = {
      id: "",
      name: appName,
      version: 1,
      logo: null,
      theme: { mode: "auto" },
      nav: { position: "rail" },
      assistantSlot: { enabled: false },
      tags: [],
      pages: [
        {
          id: "p1",
          title: "Overview",
          grid: {
            cols: 12,
            tiles: [
              {
                id: "t-plugin",
                type: "custom",
                title: "Plugin tile",
                x: 0,
                y: 0,
                w: 6,
                h: 4,
                custom: { renderer: "plugin", options: { pluginId: "records-bars" } },
              },
            ],
          },
        },
      ],
    };

    const post = await page.request.post(restAppUrl, {
      data: pluginDoc,
      headers: { "Content-Type": "application/json", Accept: "application/json", "X-XSRF-TOKEN": xsrf },
    });
    expect(post.ok(), `plugin-tile save must succeed — status ${post.status()}`).toBeTruthy();

    // Reload the app so the editor rehydrates the REST-authored plugin doc.
    await page.reload();
    await page.waitForLoadState("networkidle");

    // ----------------------------------------------------------------
    // 4. The plugin tile renders its sandboxed iframe. PluginTile.svelte mounts
    //    an <iframe title="Plugin tile" sandbox="allow-scripts"> once it fetches
    //    the installed plugin's HTML from the registry. Requires records-bars to
    //    be installed on the launcher (SAIKU_DEMO=true) — see the file header.
    //    Live-tolerant: the plugin HTML fetch + frame mount is async.
    // ----------------------------------------------------------------
    await expect(page.locator(".tile")).toHaveCount(1);
    await expect(page.locator('iframe[sandbox="allow-scripts"]')).toBeVisible({ timeout: 20000 });

    expect(
      diagnostics.filter((m) => m.includes("effect_update_depth_exceeded")),
      `effect re-entrancy must never fire. Diagnostics:\n${diagnostics.join("\n")}`,
    ).toHaveLength(0);
  });
});

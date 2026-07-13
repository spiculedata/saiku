import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright config for the saiku-ui E2E suite. Two flavours:
 *
 * - `npm run e2e`: mocked-backend tests. Playwright boots vite dev, tests intercept
 *   `/rest/saiku/*` with route handlers. Fast; no Java needed; ideal for CI + local iteration.
 * - `npm run e2e:live`: live-backend tests. Requires the launcher fat-JAR + a Pharma
 *   H2 warehouse already running on :8080 with an OSSIE datasource wired.
 *   Gated on RUN_LIVE_E2E=1.
 */
const runLive = process.env.RUN_LIVE_E2E === "1";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? "list" : "html",
  // Ignore the live spec unless RUN_LIVE_E2E=1 is set. The live spec assumes a running
  // launcher — we don't want CI to pick it up accidentally.
  testIgnore: runLive ? undefined : /live\.spec\.ts$/,
  use: {
    baseURL: runLive ? "http://localhost:8080" : "http://localhost:4173",
    trace: "on-first-retry",
    // Suppress cookie warnings from the dev server in the trace viewer.
    ignoreHTTPSErrors: true,
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  // Boot the vite preview server for the mocked path. `npm run preview` serves the built
  // static output on :4173 — running against dev's :5173 works too but produces a lot of
  // noise from HMR. Skip webServer entirely when RUN_LIVE_E2E=1 so we target the real
  // launcher.
  webServer: runLive
    ? undefined
    : {
        command: "npm run build && npm run preview -- --port 4173 --host 127.0.0.1",
        url: "http://localhost:4173",
        reuseExistingServer: !process.env.CI,
        timeout: 180_000,
      },
});

import type { Page, Route } from "@playwright/test";

/**
 * Backend fixture for the Ossie E2E specs: intercepts every request the workbench makes and
 * responds with canned JSON matching the shape the real server produces. Keeps the tests
 * independent of a running Java process — CI-friendly, quick to iterate on.
 *
 * The one route that's not fully static is {@link registerOssieBackend#lastExecuteBody},
 * which captures the most recent POST body to `/query/execute` so specs can assert on the
 * shelf-state payload that reached the server.
 */
export function registerOssieBackend(page: Page): OssieBackendHandle {
  const state: OssieBackendState = { lastExecuteBody: null };

  const routes: Array<[string | RegExp, (route: Route) => Promise<void> | void]> = [
    // Session: a signed-in admin so <Workspace> mounts.
    [
      "**/rest/saiku/session",
      async (route) => {
        if (route.request().method() === "GET") {
          await route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
              username: "admin",
              roles: ["ROLE_ADMIN", "ROLE_USER"],
              sessionid: "e2e-session-0",
              language: "en",
              isadmin: true,
            }),
          });
        } else {
          await route.fulfill({ status: 204 });
        }
      },
    ],

    // Discover: one OSSIE connection with empty catalogs. The workbench looks for
    // `type === "OSSIE"` in the connection list to render the picker entry.
    [
      "**/rest/saiku/*/discover",
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify([
            {
              name: "SALES",
              type: "OSSIE",
              catalogs: [],
            },
          ]),
        });
      },
    ],

    // Ossie model tree — served under the same discover mount.
    [
      "**/rest/saiku/*/discover/SALES/ossie-model",
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(FIXTURE_MODEL),
        });
      },
    ],

    // Query execute — capture the body so specs can inspect the shelf state that reached
    // the server, then respond with a canned CellDataSet envelope.
    [
      "**/rest/saiku/api/query/execute",
      async (route) => {
        try {
          state.lastExecuteBody = JSON.parse(route.request().postData() ?? "null");
        } catch {
          state.lastExecuteBody = null;
        }
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(FIXTURE_RESULT),
        });
      },
    ],

    // Other endpoints the app hits at boot. Return empty so nothing errors out.
    [
      "**/rest/saiku/version",
      async (route) => route.fulfill({ status: 200, contentType: "text/plain", body: "e2e-mock" }),
    ],
    [
      "**/rest/saiku/api/info/**",
      async (route) => route.fulfill({ status: 200, contentType: "application/json", body: "{}" }),
    ],
    [
      "**/rest/saiku/api/repository**",
      async (route) => route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
    ],
  ];

  for (const [pattern, handler] of routes) {
    void page.route(pattern, handler);
  }
  return {
    getLastExecuteBody: () => state.lastExecuteBody,
  };
}

export interface OssieBackendHandle {
  getLastExecuteBody: () => unknown;
}

interface OssieBackendState {
  lastExecuteBody: unknown;
}

const FIXTURE_MODEL = {
  connection: "SALES",
  name: "SALES",
  datasets: [
    {
      name: "customers",
      source: "public.customers",
      primaryKey: ["id"],
      fields: [
        { name: "id", time: false, pii: false },
        { name: "region", time: false, pii: false },
      ],
    },
    {
      name: "orders",
      source: "public.orders",
      primaryKey: ["order_id"],
      fields: [{ name: "amount", time: false, pii: false }],
    },
  ],
  metrics: [
    {
      name: "revenue",
      expression: "SUM(orders.amount)",
      aggregationKind: "SUM",
    },
  ],
  relationships: [
    {
      name: "orders_to_customers",
      from: "orders",
      to: "customers",
      fromColumns: ["customer_id"],
      toColumns: ["id"],
    },
  ],
};

const FIXTURE_RESULT = {
  width: 2,
  height: 1,
  runtime: 42,
  cellSetHeaders: [
    [
      { formattedValue: "customers.region", rawValue: "customers.region" },
      { formattedValue: "revenue", rawValue: "revenue" },
    ],
  ],
  cellSetBody: [
    [
      { formattedValue: "North", rawValue: "North" },
      { formattedValue: "350.0", rawValue: "350.0", rawNumber: 350.0 },
    ],
  ],
};

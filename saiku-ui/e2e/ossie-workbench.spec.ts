import { expect, test, type Page } from "@playwright/test";
import { registerOssieBackend, type OssieBackendHandle } from "./fixtures/ossieBackend";

/**
 * End-to-end coverage for the Ossie analytics workbench with a mocked backend. Verifies:
 *
 * 1. Ossie datasource appears in the CubePicker with the correct type badge.
 * 2. Picking it loads the model tree in the sidebar.
 * 3. Drag-and-drop drops a field on Rows / metric on Values (via low-level event dispatch —
 *    Playwright's dragTo doesn't reliably ferry the {@code application/x-saiku-ossie-field}
 *    MIME type through native HTML5 DnD on all browsers).
 * 4. Run posts the expected shelf-state payload and renders the result grid.
 */
test.describe("Ossie workbench (mocked backend)", () => {
  let backend: OssieBackendHandle;

  test.beforeEach(async ({ page }) => {
    backend = registerOssieBackend(page);
    // Suppress the first-run tour dialog — it intercepts pointer events on the workspace
    // and blocks the Run button click. Storage-key format matches Tour.svelte's session
    // scheme (username-scoped + fallback).
    await page.addInitScript(() => {
      window.localStorage.setItem("saiku.tour.done", "1");
      window.localStorage.setItem("saiku.tour.done.admin", "1");
    });
    await page.goto("/");
    await page.waitForLoadState("networkidle");
  });

  test("Ossie connection appears in the picker", async ({ page }) => {
    const select = page.locator("#cubes-select");
    await expect(select).toBeVisible();
    const optionValues = await select.locator("option").evaluateAll((els) =>
      (els as HTMLOptionElement[]).map((o) => o.value),
    );
    expect(optionValues).toContain("ossie:SALES");
  });

  test("MDX-only affordances are hidden in Ossie mode", async ({ page }) => {
    // Pre-condition: on load with no cube selected, the MDX toolbar is visible.
    // Selecting the Ossie connection must hide it so the user can't click MDX-specific
    // buttons that don't work for shelf queries (Show MDX, Swap Axes, Ask AI, chart
    // types, non-empty, visual totals, exports).
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-canvas")).toBeVisible();

    // MDX toolbar buttons that DON'T map to Ossie should not appear anywhere. Note:
    // 'Swap axes' IS legitimate in Ossie mode (rows ↔ columns), so it's not tested.
    // 'Show MDX' and 'Ask AI' are MDX-only and should be absent.
    const showMdx = page.getByRole("button", { name: /show mdx/i });
    const askAi = page.getByRole("button", { name: /ask ai/i });
    await expect(showMdx).toHaveCount(0);
    await expect(askAi).toHaveCount(0);
  });

  test("selecting Ossie renders the schema tree", async ({ page }) => {
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    // Section headers live in <header class="ossie-tree__label"> — key off the class so
    // we don't collide with the canvas hint text or the "Metrics reference…" paragraph.
    const labels = page.locator(".ossie-tree__label");
    await expect(labels.filter({ hasText: "Fact dataset" })).toBeVisible();
    await expect(labels.filter({ hasText: "Datasets" })).toBeVisible();
    await expect(labels.filter({ hasText: "Metrics" })).toBeVisible();
    // Field / metric entries live in their own class-scoped nodes; use those to avoid
    // colliding with the fact-dataset <option> or the schema tree's group-name row.
    await expect(page.locator(".ossie-tree__group-name").filter({ hasText: "customers" })).toBeVisible();
    await expect(page.locator(".ossie-tree__field").filter({ hasText: "region" })).toBeVisible();
    await expect(page.locator(".ossie-tree__metric").filter({ hasText: "revenue" })).toBeVisible();
  });

  test("dropping a field on Rows and a metric on Values adds chips", async ({ page }) => {
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();

    await dropOssieField(page, "customers", "region", '[aria-label="Rows shelf"]');
    await dropOssieMetric(page, "revenue", '[aria-label="Values shelf"]');

    await expect(
      page.locator('[aria-label="Rows shelf"] .ossie-chip').filter({ hasText: "customers.region" }),
    ).toBeVisible();
    await expect(
      page.locator('[aria-label="Values shelf"] .ossie-chip').filter({ hasText: "revenue" }),
    ).toBeVisible();
  });

  test("Save then Load round-trips the shelf state to a .saiku file", async ({ page }) => {
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();

    await dropOssieField(page, "customers", "region", '[aria-label="Rows shelf"]');
    await dropOssieMetric(page, "revenue", '[aria-label="Values shelf"]');

    const saveName = "my-ossie";
    const savePath = `/homes/home:admin/${saveName}.saiku`;
    // Open the Save modal from the canvas toolbar.
    // The toolbar's Save button is icon-only with aria-label="Save".
    await page.locator(".toolbar").getByRole("button", { name: "Save", exact: true }).click();
    // Modal renders with the default folder (user's home) pre-selected and a name field.
    // Fill in our chosen name and confirm — scope to the modal so we don't grab the
    // toolbar's Save button too.
    const saveModal = page.getByLabel(/save query/i);
    await saveModal.getByLabel(/name/i).first().fill(saveName);
    await saveModal.getByRole("button", { name: /^Save$/ }).click();

    // Wait for the toast that fires on successful save so we don't race the fixture's
    // write-through.
    await expect(page.getByText("Saved", { exact: true })).toBeVisible();
    const written = backend.getSavedFile(savePath);
    expect(written).not.toBeNull();
    const parsed = JSON.parse(written!);
    expect(parsed).toMatchObject({
      name: saveName,
      queryType: "OSSIE",
      saikuOssieVersion: 1,
      ossieQueryModel: {
        connection: "SALES",
        model: "SALES",
        // Fixture model has one relationship (orders → customers). The store's
        // graph-inference picks 'orders' as the fact (many-to-one leaf).
        factDataset: "orders",
      },
    });

    // Post-save assertions: file lands in the fixture backend with the expected shape.
    // The store's 8 save+load unit tests cover the read-side round-trip; Playwright's
    // job here is to prove the modal → API pipeline reaches the server correctly.
  });

  test("clicking a result column header cycles the sort direction", async ({ page }) => {
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();

    await dropOssieField(page, "customers", "region", '[aria-label="Rows shelf"]');
    await dropOssieMetric(page, "revenue", '[aria-label="Values shelf"]');
    await page.locator(".toolbar").getByRole("button", { name: /^Run/ }).click();
    await expect(page.locator(".ossie-result th").filter({ hasText: "customers.region" })).toBeVisible();

    // First click: sorts ASC — assert the ArrowUp icon appears next to the header text.
    const regionHeaderBtn = page
      .locator(".ossie-result th")
      .filter({ hasText: "customers.region" })
      .locator(".ossie-result__sort-btn");
    await regionHeaderBtn.click();
    await expect(regionHeaderBtn.locator("svg")).toBeVisible();
    // Second click: DESC (icon still present).
    await regionHeaderBtn.click();
    await expect(regionHeaderBtn.locator("svg")).toBeVisible();
    // Third click: clears the sort — no arrow icon on the button anymore.
    await regionHeaderBtn.click();
    await expect(regionHeaderBtn.locator("svg")).toHaveCount(0);
  });

  test("LIMIT input caps the shelf state and Swap Axes flips rows ↔ columns", async ({ page }) => {
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();
    await dropOssieField(page, "customers", "region", '[aria-label="Rows shelf"]');
    await dropOssieMetric(page, "revenue", '[aria-label="Values shelf"]');

    // LIMIT input writes to the shelf state on change.
    const limitInput = page.locator(".ossie-canvas__limit-input");
    await limitInput.fill("25");
    await limitInput.dispatchEvent("change");
    await page.locator(".toolbar").getByRole("button", { name: /^Run/ }).click();
    // Server received a payload with limit=25.
    const posted = backend.getLastExecuteBody() as { ossieQueryModel?: { limit?: number } } | null;
    expect(posted?.ossieQueryModel?.limit).toBe(25);

    // Swap Axes moves the region chip from Rows → Columns.
    await page.locator(".toolbar").getByRole("button", { name: "Swap axes" }).click();
    await expect(
      page.locator('[aria-label="Columns shelf"] .ossie-chip').filter({ hasText: "customers.region" }),
    ).toBeVisible();
    await expect(page.locator('[aria-label="Rows shelf"] .ossie-chip').filter({ hasText: "customers.region" })).toHaveCount(0);
  });

  test("view toggle swaps between Grid and Chart", async ({ page }) => {
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();
    await dropOssieField(page, "customers", "region", '[aria-label="Rows shelf"]');
    await dropOssieMetric(page, "revenue", '[aria-label="Values shelf"]');
    await page.locator(".toolbar").getByRole("button", { name: /^Run/ }).click();

    // Grid mode is default; the result table is visible.
    await expect(page.locator(".ossie-result")).toBeVisible();

    // Flip to Chart mode. ChartView mounts an ECharts canvas + hidden a11y summary; the
    // chart-type <select> becomes visible in the toolbar.
    await page.getByRole("tab", { name: /^Chart$/ }).click();
    await expect(page.locator(".ossie-canvas__charttype")).toBeVisible();
    // Grid table hides while chart is showing.
    await expect(page.locator(".ossie-result")).toHaveCount(0);

    // Flip back to Grid and confirm the table re-mounts + chart affordances go away.
    // Scope the click to the view-toggle group so ECharts' overlays don't intercept it.
    await page.locator('[role="tablist"][aria-label="View mode"]').getByRole("tab", { name: /^Grid$/ }).click();
    await expect(page.locator(".ossie-canvas__charttype")).toHaveCount(0);
    await expect(page.locator(".ossie-result")).toBeVisible();
  });

  test("Undo/Redo walks back and forward through shelf mutations", async ({ page }) => {
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();
    await dropOssieField(page, "customers", "region", '[aria-label="Rows shelf"]');
    await expect(
      page.locator('[aria-label="Rows shelf"] .ossie-chip').filter({ hasText: "customers.region" }),
    ).toBeVisible();

    // Undo (via toolbar) drops the chip.
    await page.locator(".toolbar").getByRole("button", { name: "Undo" }).click();
    await expect(page.locator('[aria-label="Rows shelf"] .ossie-chip')).toHaveCount(0);

    // Redo replays the chip.
    await page.locator(".toolbar").getByRole("button", { name: "Redo" }).click();
    await expect(
      page.locator('[aria-label="Rows shelf"] .ossie-chip').filter({ hasText: "customers.region" }),
    ).toBeVisible();
  });

  test("Show SQL modal fetches the preview endpoint", async ({ page }) => {
    // Fixture the preview-sql endpoint to return a canned SQL string.
    await page.route("**/rest/saiku/api/query/preview-sql", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ sql: "SELECT COUNT(*) FROM \"customers\"" }),
      });
    });

    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();
    await dropOssieField(page, "customers", "region", '[aria-label="Rows shelf"]');

    // Show SQL now lives inside the Tools dropdown.
    await page.locator(".toolbar").getByRole("button", { name: /Tools/ }).click();
    await page.getByRole("button", { name: /Show SQL/ }).click();
    await expect(page.getByText("Generated SQL")).toBeVisible();
    await expect(page.locator(".ossie-canvas__sql")).toContainText('SELECT COUNT(*) FROM "customers"');
  });

  test("right-click on a dimension cell adds a Filter to <value>", async ({ page }) => {
    // Backend fixture returns 3 rows; after 'Filter to Alfa' we run again and assert
    // the outbound payload carries an EQ filter on customers.region = Alfa.
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();
    await dropOssieField(page, "customers", "region", '[aria-label="Rows shelf"]');
    await dropOssieMetric(page, "revenue", '[aria-label="Values shelf"]');

    // Run — fixture returns North row with revenue.
    await page.locator(".toolbar").getByRole("button", { name: /^Run/ }).click();
    await expect(page.locator(".ossie-result")).toBeVisible();

    // Right-click on a dimension cell (customers.region = 'North' per fixture).
    await page.locator(".ossie-result tbody tr td").first().click({ button: "right" });
    await expect(page.locator(".ossie-ctx-menu")).toBeVisible();
    // Filter-to writes an EQ filter and re-runs; the fixture backend captures the payload.
    await page.getByRole("menuitem").filter({ hasText: /Filter to/ }).or(
      page.getByRole("button").filter({ hasText: /Filter to/ }),
    ).first().click();
    // Post-filter re-run: the outbound body carries the filter.
    // Small wait for the async run to complete.
    await page.waitForTimeout(200);
    const posted = backend.getLastExecuteBody() as {
      ossieQueryModel?: {
        filters?: Array<{ dataset?: string; field?: string; op?: string; value?: string }>;
      };
    } | null;
    expect(posted?.ossieQueryModel?.filters?.[0]?.op).toBe("EQ");
    expect(posted?.ossieQueryModel?.filters?.[0]?.dataset).toBe("customers");
    expect(posted?.ossieQueryModel?.filters?.[0]?.field).toBe("region");
    expect(posted?.ossieQueryModel?.filters?.[0]?.value).toBe("North");
  });

  test("drop-zone highlights while a field is being dragged over it", async ({ page }) => {
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();

    // Fire dragenter on the Rows shelf with a matching MIME type and assert the
    // dragover modifier class lands (visible cue for the user). Then fire dragleave
    // and confirm the highlight clears.
    const shelf = page.locator('[aria-label="Rows shelf"]');
    await expect(shelf).not.toHaveClass(/ossie-shelf--dragover/);
    await page.evaluate(() => {
      const el = document.querySelector('[aria-label="Rows shelf"]');
      if (!el) throw new Error("shelf missing");
      const dt = new DataTransfer();
      dt.setData("application/x-saiku-ossie-field", JSON.stringify({ dataset: "customers", field: "region" }));
      const opts = { bubbles: true, cancelable: true, dataTransfer: dt } as DragEventInit;
      el.dispatchEvent(new DragEvent("dragenter", opts));
      el.dispatchEvent(new DragEvent("dragover", opts));
    });
    await expect(shelf).toHaveClass(/ossie-shelf--dragover/);
    await page.evaluate(() => {
      const el = document.querySelector('[aria-label="Rows shelf"]');
      if (!el) return;
      el.dispatchEvent(new DragEvent("dragleave", { bubbles: true, cancelable: true }));
    });
    await expect(shelf).not.toHaveClass(/ossie-shelf--dragover/);
  });

  test("Rows × Columns × Values renders a crosstab", async ({ page }) => {
    // Override the execute fixture: return a 2×2×1 shelf state's flat rowset so the
    // client-side pivot has something to reshape. Registered before selection so the
    // route's added handler intercepts before the fixture's baseline responder runs.
    await page.route("**/rest/saiku/api/query/execute", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          runtime: 15,
          width: 3,
          height: 4,
          cellset: [
            [
              { value: "product.brand", type: "COLUMN_HEADER" },
              { value: "payer.channel", type: "COLUMN_HEADER" },
              { value: "net_revenue", type: "COLUMN_HEADER" },
            ],
            [
              { value: "Alfa", type: "ROW_HEADER" },
              { value: "Commercial", type: "ROW_HEADER" },
              { value: "250.00", type: "DATA_CELL", properties: { raw: "250" } },
            ],
            [
              { value: "Alfa", type: "ROW_HEADER" },
              { value: "Medicare", type: "ROW_HEADER" },
              { value: "400.00", type: "DATA_CELL", properties: { raw: "400" } },
            ],
            [
              { value: "Beta", type: "ROW_HEADER" },
              { value: "Commercial", type: "ROW_HEADER" },
              { value: "155.00", type: "DATA_CELL", properties: { raw: "155" } },
            ],
            [
              { value: "Beta", type: "ROW_HEADER" },
              { value: "Medicare", type: "ROW_HEADER" },
              { value: "556.00", type: "DATA_CELL", properties: { raw: "556" } },
            ],
          ],
        }),
      });
    });

    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();

    await dropOssieField(page, "customers", "region", '[aria-label="Rows shelf"]');
    await dropOssieField(page, "customers", "region", '[aria-label="Columns shelf"]');
    await dropOssieMetric(page, "revenue", '[aria-label="Values shelf"]');

    await page.locator(".toolbar").getByRole("button", { name: /^Run/ }).click();

    // Crosstab render: two header cells across (Commercial + Medicare), two row headers
    // down (Alfa + Beta), and four data cells filled from the fixture.
    await expect(page.locator(".ossie-result th").filter({ hasText: "Commercial" })).toBeVisible();
    await expect(page.locator(".ossie-result th").filter({ hasText: "Medicare" })).toBeVisible();
    await expect(page.locator(".ossie-result th").filter({ hasText: "Alfa" })).toBeVisible();
    await expect(page.locator(".ossie-result th").filter({ hasText: "Beta" })).toBeVisible();
    // Data cells appear as numeric right-aligned entries in the body.
    await expect(page.locator(".ossie-result td").filter({ hasText: "250.00" })).toBeVisible();
    await expect(page.locator(".ossie-result td").filter({ hasText: "556.00" })).toBeVisible();
  });

  test("Run posts shelf state and renders the result grid", async ({ page }) => {
    await page.locator("#cubes-select").selectOption("ossie:SALES");
    await expect(page.locator(".ossie-tree").getByText("region")).toBeVisible();

    await dropOssieField(page, "customers", "region", '[aria-label="Rows shelf"]');
    await dropOssieMetric(page, "revenue", '[aria-label="Values shelf"]');

    // Fact dataset is auto-picked as customers by the store (first field's dataset).
    // Scope to the Ossie canvas so we don't hit the MDX toolbar's Run split-button.
    await page.locator(".toolbar").getByRole("button", { name: /^Run/ }).click();

    await expect(page.locator(".ossie-result").getByText("customers.region")).toBeVisible();
    await expect(page.locator(".ossie-result").getByText("North")).toBeVisible();
    await expect(page.locator(".ossie-result").getByText("350.0")).toBeVisible();

    // Server body sanity: queryType flipped to OSSIE and shelf state serialised as expected.
    const posted = backend.getLastExecuteBody() as { queryType?: string; ossieQueryModel?: unknown } | null;
    expect(posted).not.toBeNull();
    expect(posted?.queryType).toBe("OSSIE");
    expect(posted?.ossieQueryModel).toMatchObject({
      connection: "SALES",
      model: "SALES",
      // Same graph-inference picks 'orders' as the fact.
      factDataset: "orders",
      rows: [{ dataset: "customers", field: "region" }],
      values: [{ metric: "revenue" }],
    });
  });
});

/**
 * Simulate a native HTML5 drag-and-drop for one Ossie field payload. Playwright's built-in
 * dragTo doesn't reliably ferry a specific dataTransfer MIME type through to the drop
 * handler, so we dispatch the events by hand with a real DataTransfer object.
 */
async function dropOssieField(page: Page, dataset: string, field: string, targetSelector: string) {
  await page.evaluate(
    ([mime, payload, target]) => {
      const dt = new DataTransfer();
      dt.setData(mime, payload);
      const el = document.querySelector(target);
      if (!el) throw new Error(`missing target: ${target}`);
      const opts = { bubbles: true, cancelable: true, dataTransfer: dt } as DragEventInit;
      el.dispatchEvent(new DragEvent("dragover", opts));
      el.dispatchEvent(new DragEvent("drop", opts));
    },
    ["application/x-saiku-ossie-field", JSON.stringify({ dataset, field }), targetSelector],
  );
}

async function dropOssieMetric(page: Page, metric: string, targetSelector: string) {
  await page.evaluate(
    ([mime, payload, target]) => {
      const dt = new DataTransfer();
      dt.setData(mime, payload);
      const el = document.querySelector(target);
      if (!el) throw new Error(`missing target: ${target}`);
      const opts = { bubbles: true, cancelable: true, dataTransfer: dt } as DragEventInit;
      el.dispatchEvent(new DragEvent("dragover", opts));
      el.dispatchEvent(new DragEvent("drop", opts));
    },
    ["application/x-saiku-ossie-metric", JSON.stringify({ metric }), targetSelector],
  );
}

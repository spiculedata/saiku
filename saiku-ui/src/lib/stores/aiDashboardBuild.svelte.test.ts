/*
 * Unit tests for the AI dashboard-build store. Proves the build orchestration
 * runs to completion INDEPENDENT of any drawer component (the resilience
 * guarantee), plus the plain start/finish/fail state transitions and the
 * derived elapsed getter. All collaborators are mocked — no network, no DOM.
 */
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";

const { gotoMock, beginReviewMock, assembleMock, buildMock, dangerMock } = vi.hoisted(() => ({
  gotoMock: vi.fn().mockResolvedValue(undefined),
  beginReviewMock: vi.fn(),
  assembleMock: vi.fn(() => ({ id: "d1", name: "Assembled" })),
  buildMock: vi.fn(),
  dangerMock: vi.fn(),
}));

vi.mock("$app/navigation", () => ({ goto: gotoMock }));
vi.mock("$app/paths", () => ({ base: "" }));
vi.mock("$lib/api/aiDashboard", () => ({ buildAiDashboard: buildMock }));
vi.mock("$lib/dashboard/aiDashboardAssembler", () => ({ assembleDashboard: assembleMock }));
vi.mock("$lib/stores/dashboard.svelte", () => ({ dashboardStore: { beginReview: beginReviewMock } }));
vi.mock("$lib/stores/session.svelte", () => ({
  session: {
    get current() {
      return { username: "juan" };
    },
  },
}));
vi.mock("$lib/stores/toasts.svelte", () => ({
  toasts: { danger: dangerMock, success: vi.fn() },
}));
vi.mock("$lib/stores/i18n.svelte", () => ({
  i18n: { t: (_k: string, fallback?: string) => fallback ?? _k },
}));

import { aiDashboardBuild } from "./aiDashboardBuild.svelte";

const CUBE = {
  connectionName: "foodmart",
  catalog: "FoodMart",
  schema: "FoodMart",
  cubeName: "Sales",
};

describe("aiDashboardBuild — state transitions", () => {
  beforeEach(() => {
    aiDashboardBuild.finish(); // reset to idle
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("start() enters the building state with request + startedAt, clears error", () => {
    aiDashboardBuild.fail("previous"); // seed a stale error
    aiDashboardBuild.start("sales by country");
    expect(aiDashboardBuild.building).toBe(true);
    expect(aiDashboardBuild.request).toBe("sales by country");
    expect(typeof aiDashboardBuild.startedAt).toBe("number");
    expect(aiDashboardBuild.error).toBeNull();
  });

  it("finish() leaves the building state and clears request/startedAt", () => {
    aiDashboardBuild.start("x");
    aiDashboardBuild.finish();
    expect(aiDashboardBuild.building).toBe(false);
    expect(aiDashboardBuild.request).toBe("");
    expect(aiDashboardBuild.startedAt).toBeNull();
  });

  it("fail(reason) leaves the building state and records the reason", () => {
    aiDashboardBuild.start("x");
    aiDashboardBuild.fail("couldn't build");
    expect(aiDashboardBuild.building).toBe(false);
    expect(aiDashboardBuild.error).toBe("couldn't build");
    expect(aiDashboardBuild.startedAt).toBeNull();
  });

  it("elapsedSeconds derives from startedAt (0 when idle)", () => {
    expect(aiDashboardBuild.elapsedSeconds).toBe(0);
    const t0 = 1_000_000_000;
    const spy = vi.spyOn(Date, "now").mockReturnValue(t0);
    aiDashboardBuild.start("x");
    spy.mockReturnValue(t0 + 5000);
    expect(aiDashboardBuild.elapsedSeconds).toBe(5);
  });
});

describe("aiDashboardBuild.run — orchestration (no drawer component)", () => {
  beforeEach(() => {
    aiDashboardBuild.finish();
    gotoMock.mockClear();
    beginReviewMock.mockClear();
    assembleMock.mockClear();
    (buildMock as Mock).mockReset();
    dangerMock.mockClear();
  });

  it("success → assembles, stages for review, navigates, and finishes", async () => {
    (buildMock as Mock).mockResolvedValue({
      title: "Sales Overview",
      degraded: false,
      reason: "",
      tiles: [{ title: "A", type: "table", query: {} }],
    });

    await aiDashboardBuild.run("build me a sales dashboard", CUBE);

    expect(assembleMock).toHaveBeenCalledTimes(1);
    expect(beginReviewMock).toHaveBeenCalledTimes(1);
    // navigated to a suggested new-file path under the user's home
    const [dash, savePath] = beginReviewMock.mock.calls[0];
    expect(dash).toEqual({ id: "d1", name: "Assembled" });
    expect(savePath).toMatch(/^homes\/juan\/ai-.*\.saikudash$/);
    expect(gotoMock).toHaveBeenCalledWith(`/dashboards/${savePath}`);
    expect(aiDashboardBuild.building).toBe(false);
    expect(aiDashboardBuild.error).toBeNull();
    expect(dangerMock).not.toHaveBeenCalled();
  });

  it("degrade → fail(reason), no navigation, toast shown", async () => {
    (buildMock as Mock).mockResolvedValue({
      degraded: true,
      reason: "couldn't build a dashboard for that request",
      tiles: [],
    });

    await aiDashboardBuild.run("nonsense", CUBE);

    expect(aiDashboardBuild.building).toBe(false);
    expect(aiDashboardBuild.error).toBe("couldn't build a dashboard for that request");
    expect(beginReviewMock).not.toHaveBeenCalled();
    expect(gotoMock).not.toHaveBeenCalled();
    expect(dangerMock).toHaveBeenCalledTimes(1);
  });

  it("throw → fail(message), no navigation, toast shown", async () => {
    (buildMock as Mock).mockRejectedValue(new Error("ECONNREFUSED"));

    await aiDashboardBuild.run("build", CUBE);

    expect(aiDashboardBuild.building).toBe(false);
    expect(aiDashboardBuild.error).toContain("ECONNREFUSED");
    expect(beginReviewMock).not.toHaveBeenCalled();
    expect(gotoMock).not.toHaveBeenCalled();
    expect(dangerMock).toHaveBeenCalledTimes(1);
  });

  it("re-entry guard: run() ignores a second call while already building", async () => {
    let resolve!: (v: unknown) => void;
    (buildMock as Mock).mockReturnValue(new Promise((r) => (resolve = r)));

    const first = aiDashboardBuild.run("one", CUBE);
    expect(aiDashboardBuild.building).toBe(true);
    await aiDashboardBuild.run("two", CUBE); // ignored — already building
    expect(buildMock).toHaveBeenCalledTimes(1);

    resolve({ degraded: false, title: "t", tiles: [] });
    await first;
    expect(aiDashboardBuild.building).toBe(false);
  });
});

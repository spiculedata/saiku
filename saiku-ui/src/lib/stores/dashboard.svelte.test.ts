/*
 * Unit tests for the dashboard store's REVIEW-THEN-SAVE wiring (D2 + SEC
 * fixes): the `persisted` flag distinct from `savedPath`, and the path-bound
 * one-shot `pendingReview` adoption.
 *
 * The store reads session.current.username and pushes to recentDashboards, and
 * its load/save hit the dashboards REST client. We mock all three so the tests
 * are pure and deterministic.
 */
import { beforeEach, describe, expect, it, vi, type Mock } from "vitest";

vi.mock("$lib/stores/session.svelte", () => ({
  session: {
    get current() {
      return { username: "juan" };
    },
  },
}));

vi.mock("$lib/stores/recentDashboards.svelte", () => ({
  recentDashboards: { push: vi.fn(), remove: vi.fn(), all: () => [], clear: vi.fn() },
}));

vi.mock("$lib/api/dashboards", async (importActual) => {
  const actual = await importActual<typeof import("$lib/api/dashboards")>();
  return {
    ...actual,
    loadDashboard: vi.fn(),
    saveDashboard: vi.fn(),
  };
});

import { dashboardStore } from "./dashboard.svelte";
import { loadDashboard, newDashboard, saveDashboard, type Dashboard } from "$lib/api/dashboards";

const loadDashboardMock = loadDashboard as unknown as Mock;
const saveDashboardMock = saveDashboard as unknown as Mock;

function fetchedDashboard(name: string): Dashboard {
  return { ...newDashboard(name), id: "server-id" };
}

describe("dashboardStore — REVIEW-THEN-SAVE persisted + path-bound adoption", () => {
  beforeEach(() => {
    dashboardStore.reset();
    loadDashboardMock.mockReset();
    saveDashboardMock.mockReset();
    loadDashboardMock.mockResolvedValue(fetchedDashboard("Fetched Real"));
    saveDashboardMock.mockResolvedValue({ status: "OK", path: "homes/juan/x.saikudash" });
  });

  it("(a) beginReview → persisted false; a successful save flips it true", async () => {
    const path = "homes/juan/ai-sales-abc.saikudash";
    dashboardStore.beginReview(newDashboard("AI Sales"), path);
    // Staged but nothing adopted yet — still not persisted.
    expect(dashboardStore.persisted).toBe(false);

    // The editor's load() for the staged path adopts it (no fetch).
    await dashboardStore.load(path);
    expect(loadDashboardMock).not.toHaveBeenCalled();
    expect(dashboardStore.current?.name).toBe("AI Sales");
    expect(dashboardStore.savedPath).toBe(path);
    expect(dashboardStore.dirty).toBe(true);
    expect(dashboardStore.persisted).toBe(false);

    // The user's Save writes the new file → now persisted.
    const ok = await dashboardStore.save();
    expect(ok).toBe(true);
    expect(saveDashboardMock).toHaveBeenCalledTimes(1);
    expect(dashboardStore.persisted).toBe(true);
  });

  it("a normally-fetched saved dashboard is persisted (no regression)", async () => {
    await dashboardStore.load("homes/juan/real.saikudash");
    expect(loadDashboardMock).toHaveBeenCalledTimes(1);
    expect(dashboardStore.current?.name).toBe("Fetched Real");
    expect(dashboardStore.persisted).toBe(true);
  });

  it("(b) load() of a non-matching path does NOT adopt the staged review", async () => {
    dashboardStore.beginReview(newDashboard("Staged AI"), "homes/juan/ai-x.saikudash");

    // A load for a DIFFERENT path falls through to the normal fetch.
    await dashboardStore.load("homes/juan/other.saikudash");
    expect(loadDashboardMock).toHaveBeenCalledTimes(1);
    expect(dashboardStore.current?.name).toBe("Fetched Real");
    expect(dashboardStore.persisted).toBe(true);

    // The staged review survived (path-bound, not consumed) — the correct
    // path still adopts it, dirty + not persisted.
    await dashboardStore.load("homes/juan/ai-x.saikudash");
    expect(loadDashboardMock).toHaveBeenCalledTimes(1); // no extra fetch
    expect(dashboardStore.current?.name).toBe("Staged AI");
    expect(dashboardStore.persisted).toBe(false);
    expect(dashboardStore.dirty).toBe(true);
  });

  it("(c) reset() clears the staged review so a later matching load fetches", async () => {
    dashboardStore.beginReview(newDashboard("Staged AI"), "homes/juan/ai-x.saikudash");
    dashboardStore.reset();

    await dashboardStore.load("homes/juan/ai-x.saikudash");
    // No staged review left — the matching path now fetches from the server.
    expect(loadDashboardMock).toHaveBeenCalledTimes(1);
    expect(dashboardStore.current?.name).toBe("Fetched Real");
    expect(dashboardStore.persisted).toBe(true);
  });
});

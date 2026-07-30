import { describe, it, expect } from "vitest";
import {
  importArgsFromDashboard,
  pathStem,
  slugify,
  composeAppPath,
} from "./appImport";
import type { DashboardLayout } from "$lib/api/dashboards";

const layout: DashboardLayout = { cols: 12, tiles: [] };

describe("pathStem", () => {
  it("drops folders and the .saikudash extension", () => {
    expect(pathStem("homes/admin/q4-sales.saikudash")).toBe("q4-sales");
  });

  it("drops the .saikuapp extension", () => {
    expect(pathStem("homes/admin/store.saikuapp")).toBe("store");
  });

  it("returns empty string for a folder-only / empty path", () => {
    expect(pathStem("")).toBe("");
    expect(pathStem("/")).toBe("");
  });
});

describe("importArgsFromDashboard", () => {
  it("uses the dashboard name and passes the layout through verbatim", () => {
    const args = importArgsFromDashboard({ name: "Sales", layout });
    expect(args.name).toBe("Sales");
    expect(args.layout).toBe(layout);
  });

  it("trims whitespace from the dashboard name", () => {
    expect(importArgsFromDashboard({ name: "  Sales  ", layout }).name).toBe("Sales");
  });

  it("falls back to the path stem when the dashboard has no name", () => {
    const args = importArgsFromDashboard(
      { name: "", layout },
      "homes/admin/q4-sales.saikudash",
    );
    expect(args.name).toBe("q4-sales");
  });

  it("falls back to a constant when neither name nor path yields anything", () => {
    expect(importArgsFromDashboard({ name: "", layout }).name).toBe("Imported app");
  });
});

describe("slugify", () => {
  it("lowercases, dashes and trims", () => {
    expect(slugify("My New App!")).toBe("my-new-app");
  });

  it("falls back to 'app' for an empty result", () => {
    expect(slugify("   ")).toBe("app");
  });
});

describe("composeAppPath", () => {
  it("joins folder and slugified name with the .saikuapp extension", () => {
    expect(composeAppPath("homes/admin", "Sales App")).toBe(
      "homes/admin/sales-app.saikuapp",
    );
  });

  it("handles a root (empty) folder", () => {
    expect(composeAppPath("", "Sales")).toBe("sales.saikuapp");
  });

  it("strips stray slashes on the folder", () => {
    expect(composeAppPath("/homes/admin/", "Sales")).toBe(
      "homes/admin/sales.saikuapp",
    );
  });
});

/*
 * Unit tests for normaliseDashboardPath — the path-rewriter that issue #945
 * relies on to keep new dashboards inside the user's home, even when the
 * user reaches the editor via a hand-typed URL.
 */

import { describe, expect, it } from "vitest";

import { normaliseDashboardPath } from "./dashboards";

describe("normaliseDashboardPath", () => {
  it("prefixes a bare filename with homes/<user>", () => {
    expect(normaliseDashboardPath("oops.saikudash", "juan")).toBe("homes/juan/oops.saikudash");
  });

  it("prefixes a relative subfolder path with homes/<user>", () => {
    expect(normaliseDashboardPath("marketing/q4.saikudash", "juan")).toBe(
      "homes/juan/marketing/q4.saikudash",
    );
  });

  it("leaves an existing homes/... path untouched", () => {
    expect(normaliseDashboardPath("homes/juan/foo.saikudash", "juan")).toBe(
      "homes/juan/foo.saikudash",
    );
    expect(normaliseDashboardPath("homes/admin/x.saikudash", "juan")).toBe(
      "homes/admin/x.saikudash",
    );
  });

  it("strips a leading slash and otherwise preserves an explicit absolute path", () => {
    expect(normaliseDashboardPath("/marketing/q4.saikudash", "juan")).toBe(
      "marketing/q4.saikudash",
    );
  });

  it("trims surrounding whitespace before deciding", () => {
    expect(normaliseDashboardPath("  oops.saikudash  ", "juan")).toBe("homes/juan/oops.saikudash");
  });

  it("throws on an empty or whitespace-only path", () => {
    expect(() => normaliseDashboardPath("", "juan")).toThrow(/required/);
    expect(() => normaliseDashboardPath("   ", "juan")).toThrow(/required/);
  });

  it("throws on a relative path with no current user", () => {
    expect(() => normaliseDashboardPath("oops.saikudash", "")).toThrow(/no current user/);
  });

  it("allows a leading-slash path even without a current user (admin save-as)", () => {
    expect(normaliseDashboardPath("/shared/exec.saikudash", "")).toBe("shared/exec.saikudash");
  });

  it("allows a homes/... path even without a current user", () => {
    expect(normaliseDashboardPath("homes/other/x.saikudash", "")).toBe("homes/other/x.saikudash");
  });
});

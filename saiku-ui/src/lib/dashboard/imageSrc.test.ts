import { describe, test, expect } from "vitest";
import { safeImageSrc, coerceImageFit } from "$lib/dashboard/imageSrc";

describe("safeImageSrc", () => {
  test("allows http/https absolute URLs (returned unchanged)", () => {
    expect(safeImageSrc("https://cdn.example.com/logo.png")).toBe(
      "https://cdn.example.com/logo.png",
    );
    expect(safeImageSrc("http://example.com/a.gif")).toBe("http://example.com/a.gif");
  });

  test("allows same-origin relative paths (e.g. the upload download path)", () => {
    const p = "/rest/saiku/api/dashboards/image/get?tile=t1&name=a.png";
    expect(safeImageSrc(p)).toBe(p);
  });

  test("rejects dangerous schemes", () => {
    expect(safeImageSrc("javascript:alert(1)")).toBeNull();
    expect(safeImageSrc("JavaScript:alert(1)")).toBeNull();
    expect(safeImageSrc("data:image/svg+xml,<svg onload=alert(1)>")).toBeNull();
    expect(safeImageSrc("vbscript:msgbox(1)")).toBeNull();
    expect(safeImageSrc("file:///etc/passwd")).toBeNull();
  });

  test("rejects empty / whitespace / nullish", () => {
    expect(safeImageSrc("")).toBeNull();
    expect(safeImageSrc("   ")).toBeNull();
    expect(safeImageSrc(null)).toBeNull();
    expect(safeImageSrc(undefined)).toBeNull();
  });

  test("trims surrounding whitespace before validating", () => {
    expect(safeImageSrc("  https://x.com/i.png  ")).toBe("https://x.com/i.png");
  });
});

describe("coerceImageFit", () => {
  test("passes through valid fits", () => {
    for (const f of ["contain", "cover", "fill", "scale-down"]) {
      expect(coerceImageFit(f)).toBe(f);
    }
  });
  test("defaults invalid / nullish to contain", () => {
    expect(coerceImageFit("bogus")).toBe("contain");
    expect(coerceImageFit("")).toBe("contain");
    expect(coerceImageFit(null)).toBe("contain");
    expect(coerceImageFit(undefined)).toBe("contain");
  });
});

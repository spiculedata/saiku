import { describe, expect, it } from "vitest";
import { buildShareUrl, parseShareToken } from "./shareUrl";

describe("buildShareUrl", () => {
  it("puts the token in the fragment, root-relative by default", () => {
    expect(buildShareUrl("abc123")).toBe("/share#abc123");
  });

  it("includes origin + base when provided", () => {
    expect(buildShareUrl("abc123", { origin: "https://demo.saiku.bi", base: "/ui" })).toBe(
      "https://demo.saiku.bi/ui/share#abc123",
    );
  });

  it("url-encodes the token", () => {
    expect(buildShareUrl("a b/c")).toBe("/share#a%20b%2Fc");
  });
});

describe("parseShareToken", () => {
  it("reads the token with or without a leading #", () => {
    expect(parseShareToken("#mytoken")).toBe("mytoken");
    expect(parseShareToken("mytoken")).toBe("mytoken");
  });

  it("round-trips an encoded token", () => {
    expect(parseShareToken("#a%20b%2Fc")).toBe("a b/c");
  });

  it("returns null for an empty fragment", () => {
    expect(parseShareToken("")).toBeNull();
    expect(parseShareToken("#")).toBeNull();
  });
});

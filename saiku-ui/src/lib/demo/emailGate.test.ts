import { describe, expect, it } from "vitest";
import { isCompleteCode, isValidEmail, normalizeCode } from "./emailGate";

describe("isValidEmail", () => {
  it("accepts well-formed addresses", () => {
    expect(isValidEmail("user@example.com")).toBe(true);
    expect(isValidEmail("  a.b+tag@sub.example.co.uk ")).toBe(true);
  });

  it("rejects malformed addresses", () => {
    expect(isValidEmail("notanemail")).toBe(false);
    expect(isValidEmail("missing@domain")).toBe(false);
    expect(isValidEmail("@example.com")).toBe(false);
    expect(isValidEmail("spaces in@example.com")).toBe(false);
    expect(isValidEmail("")).toBe(false);
  });
});

describe("normalizeCode", () => {
  it("keeps digits only", () => {
    expect(normalizeCode("12-34 56")).toBe("123456");
    expect(normalizeCode("abc123")).toBe("123");
  });

  it("caps at six digits", () => {
    expect(normalizeCode("1234567890")).toBe("123456");
  });

  it("handles empty / nullish input", () => {
    expect(normalizeCode("")).toBe("");
    // @ts-expect-error exercising the nullish guard
    expect(normalizeCode(undefined)).toBe("");
  });
});

describe("isCompleteCode", () => {
  it("is true only for exactly six digits", () => {
    expect(isCompleteCode("123456")).toBe(true);
    expect(isCompleteCode("12345")).toBe(false);
    expect(isCompleteCode("1234567")).toBe(false);
    expect(isCompleteCode("12345a")).toBe(false);
    expect(isCompleteCode("")).toBe(false);
  });
});

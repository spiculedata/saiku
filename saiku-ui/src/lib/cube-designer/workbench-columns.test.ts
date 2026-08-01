import { describe, it, expect } from "vitest";
import { classifyColumn, isNumericKind } from "./workbench-columns.js";

describe("classifyColumn", () => {
  it("returns undefined for a missing sqlType", () => {
    expect(classifyColumn(undefined)).toBeUndefined();
    expect(classifyColumn("")).toBeUndefined();
  });

  it("maps integer-family types to Integer", () => {
    for (const t of [
      "int",
      "integer",
      "bigint",
      "smallint",
      "tinyint",
      "serial",
      "BIGSERIAL",
    ]) {
      expect(classifyColumn(t)).toBe("Integer");
    }
  });

  it("maps real/decimal-family types to Numeric", () => {
    for (const t of [
      "numeric",
      "decimal(10,2)",
      "float",
      "double precision",
      "real",
      "money",
    ]) {
      expect(classifyColumn(t)).toBe("Numeric");
    }
  });

  it("maps boolean types to Boolean", () => {
    expect(classifyColumn("boolean")).toBe("Boolean");
    expect(classifyColumn("bool")).toBe("Boolean");
  });

  it("maps date/time types to Date", () => {
    for (const t of ["date", "timestamp", "timestamptz", "time"]) {
      expect(classifyColumn(t)).toBe("Date");
    }
  });

  it("falls back to String for text-like types", () => {
    for (const t of ["text", "varchar(255)", "char", "uuid"]) {
      expect(classifyColumn(t)).toBe("String");
    }
  });

  it("is case-insensitive", () => {
    expect(classifyColumn("INTEGER")).toBe("Integer");
    expect(classifyColumn("Numeric")).toBe("Numeric");
  });
});

describe("isNumericKind", () => {
  it("is true only for Numeric and Integer", () => {
    expect(isNumericKind("Numeric")).toBe(true);
    expect(isNumericKind("Integer")).toBe(true);
    expect(isNumericKind("String")).toBe(false);
    expect(isNumericKind("Date")).toBe(false);
    expect(isNumericKind("Boolean")).toBe(false);
    expect(isNumericKind(undefined)).toBe(false);
  });
});

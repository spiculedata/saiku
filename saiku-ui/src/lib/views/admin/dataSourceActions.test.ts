/*
 * Unit tests for the data-source row actions helpers (D6).
 *
 * The "Generate schema" button on the datasources admin view is only shown
 * when the data source has no Mondrian schema attached. We encode that
 * decision plus the target href in pure helpers so the rule is testable
 * without mounting Svelte components.
 */

import { describe, expect, it } from "vitest";

import {
  canGenerateSchema,
  generateSchemaHref,
  type GenerateSchemaTarget,
} from "./dataSourceActions";

describe("canGenerateSchema", () => {
  it("returns false when schemaName is a non-empty string", () => {
    const ds: GenerateSchemaTarget = { id: "ds-1", schemaName: "SteelWheels" };
    expect(canGenerateSchema(ds)).toBe(false);
  });

  it("returns true when schemaName is missing", () => {
    const ds: GenerateSchemaTarget = { id: "ds-1" };
    expect(canGenerateSchema(ds)).toBe(true);
  });

  it("returns true when schemaName is null", () => {
    const ds: GenerateSchemaTarget = { id: "ds-1", schemaName: null };
    expect(canGenerateSchema(ds)).toBe(true);
  });

  it("returns true when schemaName is an empty / whitespace string", () => {
    expect(canGenerateSchema({ id: "ds-1", schemaName: "" })).toBe(true);
    expect(canGenerateSchema({ id: "ds-1", schemaName: "   " })).toBe(true);
  });
});

describe("generateSchemaHref", () => {
  it("links to the schema-generator route for the data source id", () => {
    expect(generateSchemaHref({ id: "ds-1" })).toBe(
      "/admin/schema-generator/ds-1",
    );
  });

  it("percent-encodes ids that contain URL-unsafe characters", () => {
    const href = generateSchemaHref({ id: "foo bar/baz?x" });
    expect(href).toBe("/admin/schema-generator/foo%20bar%2Fbaz%3Fx");
    // the raw id must not leak through
    expect(href).not.toContain(" ");
    expect(href).not.toContain("?");
  });
});

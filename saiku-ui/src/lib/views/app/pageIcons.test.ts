/*
 * Unit tests for the page-icon vocabulary (saiku#1805).
 */

import { describe, expect, it } from "vitest";
import {
  DEFAULT_PAGE_ICON,
  PAGE_ICONS,
  PAGE_ICON_KEYS,
  pageIcon,
} from "$lib/views/app/pageIcons";

describe("pageIcon", () => {
  it("resolves every key the picker offers", () => {
    for (const k of PAGE_ICON_KEYS) {
      expect(pageIcon(k), k).not.toBe(DEFAULT_PAGE_ICON);
    }
  });

  it("falls back for an unknown, empty or absent name", () => {
    expect(pageIcon("nonesuch")).toBe(DEFAULT_PAGE_ICON);
    expect(pageIcon("")).toBe(DEFAULT_PAGE_ICON);
    expect(pageIcon(undefined)).toBe(DEFAULT_PAGE_ICON);
    expect(pageIcon(null)).toBe(DEFAULT_PAGE_ICON);
  });

  it("keeps the legacy aliases resolving, so a saved app doesn't lose its icons", () => {
    // These shipped before the vocabulary was extended. They are deliberately
    // absent from PAGE_ICON_KEYS (the picker offers the canonical name), but
    // dropping them from the MAP would blank the rail on an existing app.
    for (const [alias, canonical] of [
      ["house", "home"],
      ["cube", "boxes"],
      ["people", "users"],
    ] as const) {
      expect(pageIcon(alias), alias).toBe(pageIcon(canonical));
      expect(PAGE_ICON_KEYS).not.toContain(alias);
    }
  });

  it("offers the vocabulary a BI app actually needs", () => {
    // The eight-glyph list had nothing for a geography, estate, time or
    // finance page — the gap that made an author put `chart` on a page of maps.
    for (const k of ["map", "building", "calendar", "money", "warehouse"]) {
      expect(PAGE_ICON_KEYS).toContain(k);
    }
  });

  it("has no duplicate keys and every key maps to something", () => {
    expect(new Set(PAGE_ICON_KEYS).size).toBe(PAGE_ICON_KEYS.length);
    for (const k of PAGE_ICON_KEYS) expect(PAGE_ICONS[k], k).toBeTruthy();
  });
});

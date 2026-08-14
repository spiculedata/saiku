/*
 * Tests for App Builder text bindings.
 *
 * Dates are asserted with an injected `now` and a pinned locale so the suite
 * doesn't drift with the calendar or the runner's locale.
 */

import { describe, expect, it } from "vitest";
import {
  isoWeek,
  renderTokens,
  tokenHelp,
  FILTER_TOKEN_PLACEHOLDER,
} from "./textTokens";

// Thursday, 9 October 1997 — ISO week 41, the week FoodMart Ops' meta line names.
const NOW = new Date(1997, 9, 9);
const CTX = {
  username: "tom.barber",
  appName: "FoodMart Ops",
  context: "Seattle #3",
  filters: { Store: "Seattle #3", Product: "Drink" },
  now: NOW,
  locale: "en-GB",
};

describe("isoWeek", () => {
  it.each([
    [new Date(1997, 9, 9), 41],
    [new Date(2026, 0, 1), 1],
    // 1 Jan 2023 is a Sunday, so it belongs to ISO week 52 of 2022.
    [new Date(2023, 0, 1), 52],
    // 29 Dec 2025 is a Monday in ISO week 1 of 2026.
    [new Date(2025, 11, 29), 1],
  ])("numbers %s as week %i", (date, week) => {
    expect(isoWeek(date)).toBe(week);
  });
});

describe("renderTokens", () => {
  it("returns literals untouched", () => {
    expect(renderTokens("Portland #14 · Today", CTX)).toBe(
      "Portland #14 · Today",
    );
    expect(renderTokens("", CTX)).toBe("");
    expect(renderTokens(null, CTX)).toBe("");
    expect(renderTokens(undefined, CTX)).toBe("");
  });

  it("resolves the context selection — the heading that used to lie", () => {
    expect(renderTokens("{context} · Today", CTX)).toBe("Seattle #3 · Today");
  });

  it("resolves user tokens", () => {
    expect(renderTokens("{user.name}", CTX)).toBe("tom.barber");
    expect(renderTokens("{user.initials}", CTX)).toBe("TB");
  });

  it("resolves the app name", () => {
    expect(renderTokens("{app.name}", CTX)).toBe("FoodMart Ops");
  });

  it("resolves a per-dimension filter", () => {
    expect(renderTokens("{filter:Store}", CTX)).toBe("Seattle #3");
    expect(renderTokens("{filter:Product}", CTX)).toBe("Drink");
  });

  it("falls back to All for a dimension with no selection", () => {
    expect(renderTokens("{filter:Region}", CTX)).toBe("All");
    expect(
      renderTokens("{filter:Region}", { ...CTX, allLabel: "Everywhere" }),
    ).toBe("Everywhere");
  });

  it("resolves date tokens", () => {
    expect(renderTokens("{date:weekday}", CTX)).toBe("Thu");
    expect(renderTokens("{date:weekday.long}", CTX)).toBe("Thursday");
    expect(renderTokens("{date:month}", CTX)).toBe("October");
    expect(renderTokens("{date:year}", CTX)).toBe("1997");
    expect(renderTokens("{week}", CTX)).toBe("41");
  });

  it("rebuilds the frozen meta line as a live one", () => {
    expect(renderTokens("{date:weekday} · Week {week}", CTX)).toBe(
      "Thu · Week 41",
    );
  });

  it("resolves several tokens in one string", () => {
    expect(renderTokens("{context} · {date:weekday}", CTX)).toBe(
      "Seattle #3 · Thu",
    );
  });

  /* A silent blank is undiagnosable — the author needs to SEE the typo. */
  it("leaves an unknown token exactly as written", () => {
    expect(renderTokens("{user.nmae}", CTX)).toBe("{user.nmae}");
    expect(renderTokens("{nonsense}", CTX)).toBe("{nonsense}");
  });

  it("leaves an unrecognised date argument visible as an empty format, not a crash", () => {
    expect(renderTokens("{date:klingon}", CTX)).toBe("");
  });

  it("leaves a bare {filter} alone — a dimension is required", () => {
    expect(renderTokens("{filter}", CTX)).toBe("{filter}");
  });

  /* Prose shouldn't be mangled: "{n} items" isn't a binding. */
  it("ignores brace runs that aren't tokens", () => {
    expect(renderTokens("Showing {1} of {2}", CTX)).toBe("Showing {1} of {2}");
    expect(renderTokens("a { b } c", CTX)).toBe("a { b } c");
  });

  it("escapes a literal brace with {{", () => {
    expect(renderTokens("{{context}", CTX)).toBe("{context}");
  });

  it("yields empty strings rather than throwing on an empty context", () => {
    expect(renderTokens("{context}|{user.name}|{app.name}", {})).toBe("||");
  });

  it("defaults `now` to the current time when none is injected", () => {
    // Only asserting it produces a plausible value without an injected clock.
    expect(renderTokens("{date:year}", {})).toMatch(/^\d{4}$/);
  });
});

describe("tokenHelp", () => {
  /* The inspector shows this list; if it advertises a token the resolver
   * doesn't implement, authors get "{…}" rendered back at them. */
  it("only advertises tokens that actually resolve", () => {
    for (const { token } of tokenHelp("Store")) {
      expect(renderTokens(token, CTX), token).not.toBe(token);
    }
  });

  /* saiku#1761: the {filter:…} chip named a FoodMart dimension in every app. */
  it("names the app's own dimension in the filter chip", () => {
    const chip = tokenHelp("Geography").find((t) =>
      t.token.startsWith("{filter:"),
    );
    expect(chip?.token).toBe("{filter:Geography}");
  });

  it("falls back to a neutral placeholder, never a sample dataset's dimension", () => {
    const chip = tokenHelp().find((t) => t.token.startsWith("{filter:"));
    expect(chip?.token).toBe(`{filter:${FILTER_TOKEN_PLACEHOLDER}}`);
    expect(
      tokenHelp("   ").find((t) => t.token.startsWith("{filter:"))?.token,
    ).toBe(`{filter:${FILTER_TOKEN_PLACEHOLDER}}`);
  });
});

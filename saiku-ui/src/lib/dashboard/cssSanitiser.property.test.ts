import { describe, test } from "vitest";
import fc from "fast-check";
import { sanitiseAndScopeCss } from "./cssSanitiser";

describe("cssSanitiser properties", () => {
  test("output never contains @import, expression(, behavior:, -moz-binding, or position:fixed", () => {
    fc.assert(
      fc.property(fc.string(), (s) => {
        const out = sanitiseAndScopeCss(s + " .x{color:red}", '[data-saiku-app="a"]').toLowerCase();
        return (
          !out.includes("@import") &&
          !out.includes("expression(") &&
          !out.includes("behavior:") &&
          !out.includes("-moz-binding") &&
          !/position\s*:\s*fixed/.test(out)
        );
      }),
      { numRuns: 500 },
    );
  });
});

import { describe, it, expect } from "vitest";
import { drillthroughPosition } from "./drillthroughCoord";

describe("drillthroughPosition", () => {
  it("builds {col}:{row} for valid indices", () => {
    expect(drillthroughPosition(2, 5)).toBe("2:5");
  });

  it("treats zero as a valid index for both axes", () => {
    expect(drillthroughPosition(0, 0)).toBe("0:0");
    expect(drillthroughPosition(0, 3)).toBe("0:3");
    expect(drillthroughPosition(3, 0)).toBe("3:0");
  });

  it("returns null when the column index is negative", () => {
    expect(drillthroughPosition(-1, 0)).toBeNull();
  });

  it("returns null when the row index is negative", () => {
    expect(drillthroughPosition(0, -2)).toBeNull();
  });

  it("returns null for non-integer indices", () => {
    expect(drillthroughPosition(1.5, 2)).toBeNull();
    expect(drillthroughPosition(2, 0.1)).toBeNull();
  });

  it("returns null for NaN / non-finite indices", () => {
    expect(drillthroughPosition(NaN, 0)).toBeNull();
    expect(drillthroughPosition(0, NaN)).toBeNull();
    expect(drillthroughPosition(Infinity, 0)).toBeNull();
  });
});

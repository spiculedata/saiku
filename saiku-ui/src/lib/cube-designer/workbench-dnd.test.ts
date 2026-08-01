import { describe, it, expect } from "vitest";
import {
  COLUMN_MIME,
  TABLE_MIME,
  JOINGROUP_MIME,
  FACTS_MEASURE_MIME,
  ATTRIBUTE_MIME,
  LEVEL_MOVE_MIME,
  PANE_REORDER_MIME,
  FACTS_PANE_REORDER_MIME,
  readColumnPayload,
  readTablePayload,
  readJoinGroupPayload,
  readAttributeDrag,
  readLevelMoveDrag,
  readFactsMeasureDrag,
  isColumnDrag,
  isAnyWorkbenchDrag,
  isContentDrag,
  isPaneReorderDrag,
  isFactsPaneReorderDrag,
} from "./workbench-dnd.js";

/** Minimal DataTransfer stand-in — the parsers only read `.types` +
 *  `.getData()`. */
function dragEvent(data: Record<string, string>): DragEvent {
  return {
    dataTransfer: {
      types: Object.keys(data),
      getData: (mime: string) => data[mime] ?? "",
    },
  } as unknown as DragEvent;
}

/** A DragEvent with no dataTransfer at all. */
const emptyEvent = {} as unknown as DragEvent;

describe("column payload", () => {
  it("round-trips a JSON column payload", () => {
    const e = dragEvent({
      [COLUMN_MIME]: JSON.stringify({
        tableId: "t",
        columnName: "c",
        columnType: "Numeric",
      }),
    });
    expect(readColumnPayload(e)).toEqual({
      tableId: "t",
      columnName: "c",
      columnType: "Numeric",
    });
    expect(isColumnDrag(e)).toBe(true);
  });

  it("returns null for a missing or malformed payload", () => {
    expect(readColumnPayload(dragEvent({}))).toBeNull();
    expect(readColumnPayload(emptyEvent)).toBeNull();
    expect(
      readColumnPayload(dragEvent({ [COLUMN_MIME]: "not json" })),
    ).toBeNull();
  });
});

describe("table + join-group payloads", () => {
  it("parses a table payload", () => {
    const e = dragEvent({ [TABLE_MIME]: JSON.stringify({ tableId: "t1" }) });
    expect(readTablePayload(e)).toEqual({ tableId: "t1" });
  });

  it("parses a join-group payload", () => {
    const e = dragEvent({
      [JOINGROUP_MIME]: JSON.stringify({ key: "k", tableIds: ["a", "b"] }),
    });
    expect(readJoinGroupPayload(e)).toEqual({ key: "k", tableIds: ["a", "b"] });
  });

  it("return null on malformed JSON", () => {
    expect(readTablePayload(dragEvent({ [TABLE_MIME]: "{" }))).toBeNull();
    expect(
      readJoinGroupPayload(dragEvent({ [JOINGROUP_MIME]: "{" })),
    ).toBeNull();
  });
});

describe("attribute + level-move payloads", () => {
  it("parses an attribute drag", () => {
    const e = dragEvent({
      [ATTRIBUTE_MIME]: JSON.stringify({ tableId: "t", columnName: "c" }),
    });
    expect(readAttributeDrag(e)).toEqual({ tableId: "t", columnName: "c" });
  });

  it("parses a level-move drag", () => {
    const e = dragEvent({
      [LEVEL_MOVE_MIME]: JSON.stringify({
        dimId: "d",
        hierId: "h",
        levelId: "l",
      }),
    });
    expect(readLevelMoveDrag(e)).toEqual({
      dimId: "d",
      hierId: "h",
      levelId: "l",
    });
  });

  it("returns null when the MIME is absent", () => {
    expect(readAttributeDrag(dragEvent({}))).toBeNull();
    expect(readLevelMoveDrag(dragEvent({}))).toBeNull();
  });
});

describe("facts-measure drag", () => {
  it("returns the raw column-name string payload", () => {
    expect(
      readFactsMeasureDrag(dragEvent({ [FACTS_MEASURE_MIME]: "unit_sales" })),
    ).toBe("unit_sales");
  });

  it("returns null when the facts-measure MIME is absent", () => {
    expect(readFactsMeasureDrag(dragEvent({}))).toBeNull();
  });
});

describe("drag-kind predicates", () => {
  it("isAnyWorkbenchDrag matches column / table / join-group drags only", () => {
    expect(isAnyWorkbenchDrag(dragEvent({ [COLUMN_MIME]: "{}" }))).toBe(true);
    expect(isAnyWorkbenchDrag(dragEvent({ [TABLE_MIME]: "{}" }))).toBe(true);
    expect(isAnyWorkbenchDrag(dragEvent({ [JOINGROUP_MIME]: "{}" }))).toBe(
      true,
    );
    expect(isAnyWorkbenchDrag(dragEvent({ [ATTRIBUTE_MIME]: "{}" }))).toBe(
      false,
    );
  });

  it("isContentDrag matches attribute or level-move drags", () => {
    expect(isContentDrag(dragEvent({ [ATTRIBUTE_MIME]: "{}" }))).toBe(true);
    expect(isContentDrag(dragEvent({ [LEVEL_MOVE_MIME]: "{}" }))).toBe(true);
    expect(isContentDrag(dragEvent({ [COLUMN_MIME]: "{}" }))).toBe(false);
  });

  it("pane-reorder predicates are side-specific", () => {
    expect(isPaneReorderDrag(dragEvent({ [PANE_REORDER_MIME]: "0" }))).toBe(
      true,
    );
    expect(
      isPaneReorderDrag(dragEvent({ [FACTS_PANE_REORDER_MIME]: "0" })),
    ).toBe(false);
    expect(
      isFactsPaneReorderDrag(dragEvent({ [FACTS_PANE_REORDER_MIME]: "0" })),
    ).toBe(true);
    expect(
      isFactsPaneReorderDrag(dragEvent({ [PANE_REORDER_MIME]: "0" })),
    ).toBe(false);
  });

  it("all MIME strings are distinct", () => {
    const mimes = [
      COLUMN_MIME,
      TABLE_MIME,
      JOINGROUP_MIME,
      FACTS_MEASURE_MIME,
      ATTRIBUTE_MIME,
      LEVEL_MOVE_MIME,
      PANE_REORDER_MIME,
      FACTS_PANE_REORDER_MIME,
    ];
    expect(new Set(mimes).size).toBe(mimes.length);
  });
});

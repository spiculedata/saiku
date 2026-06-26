/*
 * Round-trip test for the Arrow IPC client adapter.
 *
 * Strategy: build a small Arrow IPC stream in-test using the *same*
 * apache-arrow library the client uses, so we don't need to ship a binary
 * fixture or shell out to Java. The schema we construct mirrors
 * `ArrowCellsetWriter` — `saiku.cellset` schema metadata plus
 * `r{i}_{value,uniqueName,dimension,hierarchy,level}` and
 * `c{j}_{raw,fmt}` columns. We then parse the bytes with `parseArrowExecute`
 * and assert the reconstructed `QueryResult` matches a hand-built JSON
 * equivalent.
 */

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  Schema,
  Table,
  tableToIPC,
  vectorFromArray,
} from "apache-arrow";

import { parseArrowExecute } from "./arrow";

function buildFixture(): ArrayBuffer {
  // Two data rows, one row-header column, two data columns. The second data
  // column exercises a formatted value that differs from the raw number; the
  // first uses the empty-fmt path where the formatted equals the raw string.
  // apache-arrow's Table constructor accepts a record of vectors but the
  // typings narrow the vector type per entry; cast to any to mix utf8 +
  // float columns in one map.
  const cells = {
    r0_value: vectorFromArray(["California", "Oregon"]),
    r0_uniqueName: vectorFromArray(["[Store].[CA]", "[Store].[OR]"]),
    r0_dimension: vectorFromArray(["Store", "Store"]),
    r0_hierarchy: vectorFromArray(["[Store]", "[Store]"]),
    r0_level: vectorFromArray(["[Store].[State]", "[Store].[State]"]),
    c0_raw: vectorFromArray(new Float64Array([100, 200])),
    c0_fmt: vectorFromArray(["", ""]),
    c1_raw: vectorFromArray(new Float64Array([10.5, 20.5])),
    c1_fmt: vectorFromArray(["$10.50", "$20.50"]),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as unknown as Record<string, any>;

  const table = new Table(cells);
  const metadata = new Map<string, string>();
  metadata.set(
    "saiku.cellset",
    JSON.stringify({
      rowHeaderColCount: 1,
      columnHeaderRows: [
        ["Unit Sales", "Store Sales"],
      ],
      runtimeMs: 42,
      width: 3,
      height: 2,
      mdx: "SELECT ... FROM [Sales]",
      queryName: "state_sales",
    }),
  );
  const schemaWithMeta = new Schema(table.schema.fields, metadata);
  const tableWithMeta = new Table(schemaWithMeta, table.batches);

  const ipc = tableToIPC(tableWithMeta, "stream");
  // Copy into a fresh ArrayBuffer (not SharedArrayBuffer) so the return type
  // satisfies ArrayBuffer strictly.
  const ab = new ArrayBuffer(ipc.byteLength);
  new Uint8Array(ab).set(ipc);
  return ab;
}

describe("parseArrowExecute", () => {
  it("round-trips an Arrow IPC stream into the legacy QueryResult shape", async () => {
    const buffer = buildFixture();
    const result = await parseArrowExecute(buffer);

    // Shape / metadata pass-through
    expect(result.height).toBe(2);
    expect(result.width).toBe(3);
    expect(result.runtime).toBe(42);

    // Cellset: 1 column-header row + 2 data rows = 3 rows total
    expect(result.cellset).toBeDefined();
    expect(result.cellset.length).toBe(3);

    // Header row: first cell is ROW_HEADER_HEADER, then two COLUMN_HEADERs.
    const header = result.cellset[0];
    expect(header.length).toBe(3);
    expect(header[0].type).toBe("ROW_HEADER_HEADER");
    expect(header[1]).toMatchObject({ type: "COLUMN_HEADER", value: "Unit Sales" });
    expect(header[2]).toMatchObject({ type: "COLUMN_HEADER", value: "Store Sales" });

    // First data row
    const row1 = result.cellset[1];
    expect(row1[0]).toMatchObject({
      type: "ROW_HEADER",
      value: "California",
    });
    expect(row1[0].properties).toMatchObject({
      uniquename: "[Store].[CA]",
      dimension: "Store",
      hierarchy: "[Store]",
      level: "[Store].[State]",
    });
    // fmt == "" -> display falls back to raw.toString()
    expect(row1[1]).toMatchObject({ type: "DATA_CELL", value: "100" });
    expect(row1[1].properties?.raw).toBe("100");
    // fmt != "" -> display = fmt
    expect(row1[2]).toMatchObject({ type: "DATA_CELL", value: "$10.50" });
    expect(row1[2].properties?.raw).toBe("10.5");

    // Second data row spot-check
    const row2 = result.cellset[2];
    expect(row2[0].value).toBe("Oregon");
    expect(row2[1].value).toBe("200");
    expect(row2[2].value).toBe("$20.50");
  });

  it("does not import apache-arrow at the top level of query.ts", () => {
    // Kept as a grep-level guard: query.ts must lazy-import the adapter so
    // apache-arrow is bundled as a deferred chunk. If somebody flips the
    // import to a static one this test will fail, forcing them to look at
    // the bundle cost.
    const queryTs = readFileSync(
      resolve(__dirname, "query.ts"),
      "utf8",
    );
    expect(queryTs).not.toMatch(/^import .* from ['"]apache-arrow['"]/m);
    expect(queryTs).not.toMatch(/^import .* from ['"]\$lib\/api\/arrow['"]/m);
    // The Arrow adapter should only be pulled in via dynamic import.
    expect(queryTs).toMatch(/import\(\s*['"]\$lib\/api\/arrow['"]\s*\)/);
  });
});

import { describe, it, expect, beforeEach } from "vitest";
import { saveQuery, listQueries, removeQuery } from "../src/lib/queryStore";

beforeEach(() => {
  const store: Record<string, unknown> = {};
  (globalThis as any).Office = { context: { document: { settings: {
    get: (k: string) => store[k], set: (k: string, v: unknown) => { store[k] = v; },
    saveAsync: (cb: (r: { status: string }) => void) => cb({ status: "succeeded" })
  } } } };
});

describe("queryStore", () => {
  it("saves and lists queries", async () => {
    await saveQuery({ id: "q1", title: "Sales", sheet: "Sheet1", request: { cube: "c", measures: [], rows: [], nonEmpty: true } });
    const list = await listQueries();
    expect(list.map((q) => q.id)).toEqual(["q1"]);
  });
  it("replaces a query with the same id (no dupes)", async () => {
    const base = { title: "S", sheet: "Sheet1", request: { cube: "c", measures: [], rows: [], nonEmpty: true } };
    await saveQuery({ id: "q1", ...base });
    await saveQuery({ id: "q1", ...base, title: "S2" });
    const list = await listQueries();
    expect(list.length).toBe(1);
    expect(list[0].title).toBe("S2");
  });
  it("removes a query", async () => {
    await saveQuery({ id: "q1", title: "S", sheet: "Sheet1", request: { cube: "c", measures: [], rows: [], nonEmpty: true } });
    await removeQuery("q1");
    expect(await listQueries()).toEqual([]);
  });
});

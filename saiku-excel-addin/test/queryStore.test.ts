import { describe, it, expect, beforeEach } from "vitest";
import { saveQuery, listQueries, removeQuery, saveRegistry, loadRegistry, workbookPayload } from "../src/lib/queryStore";

beforeEach(() => {
  const store: Record<string, unknown> = {};
  (globalThis as any).Office = { context: { document: { settings: {
    get: (k: string) => store[k], set: (k: string, v: unknown) => { store[k] = v; },
    saveAsync: (cb: (r: { status: string }) => void) => cb({ status: "succeeded" })
  } } } };
});

describe("queryStore (workbook, server-scoped)", () => {
  it("saves and lists queries (with serverId)", async () => {
    await saveQuery({ id: "q1", serverId: "srv1", title: "Sales", sheet: "Sheet1", request: { cube: "c", measures: [], rows: [], nonEmpty: true } });
    const list = await listQueries();
    expect(list.map((q) => q.id)).toEqual(["q1"]);
    expect(list[0].serverId).toBe("srv1");
  });

  it("replaces a query with the same id (no dupes)", async () => {
    const base = { serverId: "srv1", title: "S", sheet: "Sheet1", request: { cube: "c", measures: [], rows: [], nonEmpty: true } };
    await saveQuery({ id: "q1", ...base });
    await saveQuery({ id: "q1", ...base, title: "S2" });
    const list = await listQueries();
    expect(list.length).toBe(1);
    expect(list[0].title).toBe("S2");
  });

  it("removes a query", async () => {
    await saveQuery({ id: "q1", serverId: "srv1", title: "S", sheet: "Sheet1", request: { cube: "c", measures: [], rows: [], nonEmpty: true } });
    await removeQuery("q1");
    expect(await listQueries()).toEqual([]);
  });

  it("saves a query with its serverId", async () => {
    await saveQuery({ id: "q1", serverId: "srv1", title: "T", sheet: "S", request: { cube: "c", measures: [], rows: [], nonEmpty: true } });
    expect((await listQueries())[0].serverId).toBe("srv1");
  });

  it("persists the registry (servers + active) in the workbook", async () => {
    await saveRegistry({ servers: [{ id: "srv1", label: "Acme", baseUrl: "https://acme.test", strategy: "basic" }], activeId: "srv1" });
    expect((await loadRegistry()).activeId).toBe("srv1");
  });

  it("workbookPayload contains servers + queries but NO credential fields", async () => {
    await saveRegistry({ servers: [{ id: "srv1", label: "Acme", baseUrl: "https://acme.test", strategy: "basic" }], activeId: "srv1" });
    await saveQuery({ id: "q1", serverId: "srv1", title: "T", sheet: "S", request: { cube: "c", measures: [], rows: [], nonEmpty: true } });
    const json = JSON.stringify(workbookPayload());
    expect(json).toContain("srv1");
    expect(json.toLowerCase()).not.toContain("password");
    expect(json.toLowerCase()).not.toContain("accesstoken");
  });
});

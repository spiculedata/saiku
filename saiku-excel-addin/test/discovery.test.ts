import { describe, it, expect, vi } from "vitest";
import { listCubes, getSchema, searchMembers } from "../src/lib/discovery";
const json = (o: unknown) => new Response(JSON.stringify(o), { status: 200, headers: { "content-type": "application/json" } });
describe("discovery", () => {
  it("lists cubes", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json([{ connectionName: "c", catalog: "cat", schema: "s", cubeName: "Sales", cubeCaption: "Sales" }])));
    const cubes = await listCubes();
    expect(cubes[0].cubeName).toBe("Sales");
  });
  it("fetches schema by cube id", async () => {
    const f = vi.fn<(url: string, opts?: RequestInit) => Promise<Response>>(async () => json({ cubeName: "Sales", measures: {}, dimensions: {} }));
    vi.stubGlobal("fetch", f);
    await getSchema("c/cat/s/Sales");
    expect(f.mock.calls[0][0]).toBe("/rest/saiku/api/ai/schema/c/cat/s/Sales");
  });
  it("searchMembers sends cubeId + dimension + hierarchy + level + q", async () => {
    const f = vi.fn<(url: string, opts?: RequestInit) => Promise<Response>>(async () => json([]));
    vi.stubGlobal("fetch", f);
    await searchMembers("c/cat/s/Sales", { dimension: "Product", hierarchy: "Products", level: "Product Family" }, "Dri");
    const url = new URL("http://x" + (f.mock.calls[0][0] as string));
    expect(url.pathname).toBe("/rest/saiku/api/ai/members/search");
    expect(url.searchParams.get("cubeId")).toBe("c/cat/s/Sales");
    expect(url.searchParams.get("dimension")).toBe("Product");
    expect(url.searchParams.get("hierarchy")).toBe("Products");
    expect(url.searchParams.get("level")).toBe("Product Family");
    expect(url.searchParams.get("q")).toBe("Dri");
  });
});

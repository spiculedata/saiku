import { describe, it, expect, vi } from "vitest";
import { listCubes, getSchema } from "../src/lib/discovery";
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
});

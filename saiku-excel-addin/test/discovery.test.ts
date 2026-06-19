import { describe, it, expect, vi } from "vitest";
import { listCubes, getSchema, searchMembers } from "../src/lib/discovery";
import type { ServerCtx } from "../src/lib/http";

const ctx: ServerCtx = {
  baseUrl: "https://x.test",
  credential: { kind: "basic", username: "u", password: "p" }
};

const json = (o: unknown) => new Response(JSON.stringify(o), { status: 200, headers: { "content-type": "application/json" } });

describe("discovery", () => {
  it("lists cubes — URL is baseUrl + /rest/..., Authorization is set, credentials omit", async () => {
    const f = vi.fn<(url: string, opts?: RequestInit) => Promise<Response>>(async () => json([{ connectionName: "c", catalog: "cat", schema: "s", cubeName: "Sales", cubeCaption: "Sales" }]));
    vi.stubGlobal("fetch", f);
    const cubes = await listCubes(ctx);
    expect(cubes[0].cubeName).toBe("Sales");
    const [url, opts] = f.mock.calls[0];
    expect(url).toBe("https://x.test/rest/saiku/api/ai/cubes");
    expect((opts as RequestInit).credentials).toBe("omit");
    expect(((opts as RequestInit).headers as Record<string, string>)["Authorization"]).toMatch(/^Basic /);
  });

  it("fetches schema by cube id", async () => {
    const f = vi.fn<(url: string, opts?: RequestInit) => Promise<Response>>(async () => json({ cubeName: "Sales", measures: {}, dimensions: {} }));
    vi.stubGlobal("fetch", f);
    await getSchema(ctx, "c/cat/s/Sales");
    expect(f.mock.calls[0][0]).toBe("https://x.test/rest/saiku/api/ai/schema/c/cat/s/Sales");
  });

  it("searchMembers sends cubeId + dimension + hierarchy + level + q", async () => {
    const f = vi.fn<(url: string, opts?: RequestInit) => Promise<Response>>(async () => json([]));
    vi.stubGlobal("fetch", f);
    await searchMembers(ctx, "c/cat/s/Sales", { dimension: "Product", hierarchy: "Products", level: "Product Family" }, "Dri");
    const url = new URL(f.mock.calls[0][0] as string);
    expect(url.pathname).toBe("/rest/saiku/api/ai/members/search");
    expect(url.searchParams.get("cubeId")).toBe("c/cat/s/Sales");
    expect(url.searchParams.get("dimension")).toBe("Product");
    expect(url.searchParams.get("hierarchy")).toBe("Products");
    expect(url.searchParams.get("level")).toBe("Product Family");
    expect(url.searchParams.get("q")).toBe("Dri");
    // Verify cross-origin headers
    const [, opts] = f.mock.calls[0];
    expect((opts as RequestInit).credentials).toBe("omit");
    expect(((opts as RequestInit).headers as Record<string, string>)["Authorization"]).toMatch(/^Basic /);
  });
});

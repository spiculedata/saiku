import { describe, it, expect, vi } from "vitest";
import { login, session } from "../src/lib/auth";

describe("auth", () => {
  it("login posts form-encoded creds", async () => {
    const f = vi.fn<(url: string, opts: RequestInit) => Promise<Response>>(async () => new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", f);
    await login("admin", "admin");
    const [url, opts] = f.mock.calls[0];
    expect(url).toBe("/rest/saiku/session");
    expect(opts.method).toBe("POST");
    expect(opts.body).toBe("username=admin&password=admin");
    expect((opts.headers as Record<string, string>)["Content-Type"]).toBe("application/x-www-form-urlencoded");
  });
  it("session returns roles", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      new Response(JSON.stringify({ username: "admin", roles: ["ROLE_ADMIN"] }),
        { status: 200, headers: { "content-type": "application/json" } })));
    expect((await session()).username).toBe("admin");
  });
});

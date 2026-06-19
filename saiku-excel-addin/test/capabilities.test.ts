import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { fetchCapabilities, chooseStrategies } from "../src/lib/capabilities";
import type { Capabilities } from "../src/lib/types";

describe("fetchCapabilities", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("GETs the correct capabilities URL and returns parsed JSON", async () => {
    const caps: Capabilities = { basic: true, oidc: { issuer: "https://idp.example.com", clientId: "saiku" } };
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true,
      json: async () => caps,
    });

    const result = await fetchCapabilities("https://myserver.example.com");

    expect(fetch).toHaveBeenCalledWith("https://myserver.example.com/rest/saiku/info/auth-capabilities");
    expect(result).toEqual(caps);
  });

  it("throws when response is not ok", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: false, status: 503 });

    await expect(fetchCapabilities("https://myserver.example.com")).rejects.toThrow("HTTP 503");
  });
});

describe("chooseStrategies", () => {
  it("returns ['oidc', 'basic'] when both are available", () => {
    const caps: Capabilities = { basic: true, oidc: { issuer: "https://idp.example.com", clientId: "saiku" } };
    expect(chooseStrategies(caps)).toEqual(["oidc", "basic"]);
  });

  it("returns ['basic'] when only basic is available", () => {
    const caps: Capabilities = { basic: true };
    expect(chooseStrategies(caps)).toEqual(["basic"]);
  });

  it("returns ['oidc'] when only oidc is available", () => {
    const caps: Capabilities = { basic: false, oidc: { issuer: "https://idp.example.com", clientId: "saiku" } };
    expect(chooseStrategies(caps)).toEqual(["oidc"]);
  });

  it("returns [] when nothing is available", () => {
    const caps: Capabilities = { basic: false };
    expect(chooseStrategies(caps)).toEqual([]);
  });
});

import { describe, it, expect, vi, beforeEach } from "vitest";

const { askAiMock, FakeTransportError } = vi.hoisted(() => {
  class FakeTransportError extends Error {}
  return { askAiMock: vi.fn(), FakeTransportError };
});
vi.mock("$lib/api/aiAsk", () => ({
  askAi: (req: unknown) => askAiMock(req),
  AiAskTransportError: FakeTransportError,
}));

import { SUMMARIZE_PROMPT, buildSummarizeRequest, generateSummary } from "./summarize";

const cube = { connectionName: "c", catalog: "cat", schema: "sch", cubeName: "Accounts" };

beforeEach(async () => {
  askAiMock.mockReset();
});

describe("buildSummarizeRequest", () => {
  it("forces the insight tool and carries the prompt + digest", () => {
    const req = buildSummarizeRequest(cube, "DIGEST");
    expect(req).toEqual({ cube, question: SUMMARIZE_PROMPT, cellsetDigest: "DIGEST", forceTool: "insight" });
  });
  it("passes undefined digest through", () => {
    expect(buildSummarizeRequest(cube, undefined).cellsetDigest).toBeUndefined();
  });
});

describe("generateSummary", () => {
  it("returns the insight markdown on success", async () => {
    askAiMock.mockResolvedValue({ insight: { markdown: "Large leads at 7,000." } });
    await expect(generateSummary(cube, "D")).resolves.toBe("Large leads at 7,000.");
    expect(askAiMock).toHaveBeenCalledWith(buildSummarizeRequest(cube, "D"));
  });
  it("returns null when the response has no insight", async () => {
    askAiMock.mockResolvedValue({});
    await expect(generateSummary(cube, "D")).resolves.toBeNull();
  });
  it("returns null on a transport error", async () => {
    askAiMock.mockRejectedValue(new FakeTransportError("down"));
    await expect(generateSummary(cube, "D")).resolves.toBeNull();
  });
  it("rethrows an unexpected (non-transport) error", async () => {
    askAiMock.mockRejectedValue(new Error("boom"));
    await expect(generateSummary(cube, "D")).rejects.toThrow("boom");
  });
});

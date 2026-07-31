/**
 * Unit tests for the DimSum agent-loop request/response shaping.
 */
import { describe, it, expect, vi } from "vitest";
import { buildDimSumRequestBody, postDimSumTurn } from "./dimsum-agent.js";
import type { AnthropicBlock, ChatMessage } from "./ai-chat-types";

describe("buildDimSumRequestBody", () => {
  it("strips the display-only ts and carries the canvas summary", () => {
    const messages: ChatMessage[] = [
      { role: "user", content: "draft me a cube", ts: 111 },
      { role: "assistant", content: [{ type: "text", text: "ok" }], ts: 222 },
    ];

    const body = buildDimSumRequestBody(messages, "SUMMARY");

    expect(body.canvasSummary).toBe("SUMMARY");
    expect(body.messages).toEqual([
      { role: "user", content: "draft me a cube" },
      { role: "assistant", content: [{ type: "text", text: "ok" }] },
    ]);
    // The ts field must not leak to the wire.
    expect(body.messages.every((m) => !("ts" in m))).toBe(true);
  });
});

describe("postDimSumTurn", () => {
  function jsonResponse(status: number, payload: unknown): Response {
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => payload,
    } as unknown as Response;
  }

  it("posts to the DimSum endpoint and returns the assistant content blocks", async () => {
    const blocks: AnthropicBlock[] = [{ type: "text", text: "done" }];
    const fetchImpl = vi.fn(async () =>
      jsonResponse(200, { stopReason: "end_turn", content: blocks }),
    );

    const out = await postDimSumTurn(
      [{ role: "user", content: "hi", ts: 1 }],
      "SUMMARY",
      fetchImpl as unknown as typeof fetch,
    );

    expect(out).toEqual(blocks);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ];
    expect(url).toBe("/api/inference/dimsum");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string).canvasSummary).toBe("SUMMARY");
  });

  it("returns an empty array when the payload has no content", async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(200, { stopReason: "end_turn" }),
    );
    const out = await postDimSumTurn(
      [],
      "S",
      fetchImpl as unknown as typeof fetch,
    );
    expect(out).toEqual([]);
  });

  it("throws a gateway-shaped error on a non-2xx response", async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(500, { message: "kaboom" }),
    );
    await expect(
      postDimSumTurn([], "S", fetchImpl as unknown as typeof fetch),
    ).rejects.toThrow();
  });
});

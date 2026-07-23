/*
 * Tests for classifyChainEnvelope — the pure dispatch classifier the
 * chained-ask ("Build & report") drawer path uses to route step/final
 * envelopes from askAiStream to the right turn kind.
 */

import { describe, it, expect } from "vitest";
import { classifyChainEnvelope } from "./chainStep";
import type { AskResponse } from "./aiAsk";

describe("classifyChainEnvelope", () => {
  it("classifies a built-query envelope as 'query'", () => {
    const env: AskResponse = { degraded: false, request: { measures: [] } };
    expect(classifyChainEnvelope(env)).toBe("query");
  });

  it("classifies an insight envelope as 'report'", () => {
    const env: AskResponse = { degraded: false, insight: { markdown: "# hi" } };
    expect(classifyChainEnvelope(env)).toBe("report");
  });

  it("classifies a degraded envelope as 'degraded' even when request is also present", () => {
    const env: AskResponse = { degraded: true, reason: "not configured", request: { measures: [] } };
    expect(classifyChainEnvelope(env)).toBe("degraded");
  });

  it("classifies an emailDraft envelope as 'emailDraft'", () => {
    const env: AskResponse = { degraded: false, emailDraft: { summary: "draft" } };
    expect(classifyChainEnvelope(env)).toBe("emailDraft");
  });

  it("classifies a viewChange envelope as 'viewChange'", () => {
    const env: AskResponse = { degraded: false, viewChange: { viewMode: "chart", chartType: "bar" } };
    expect(classifyChainEnvelope(env)).toBe("viewChange");
  });

  it("classifies an empty non-degraded envelope as 'unknown'", () => {
    const env: AskResponse = { degraded: false };
    expect(classifyChainEnvelope(env)).toBe("unknown");
  });

  it("prioritizes insight over a co-present viewChange/emailDraft (shouldn't happen, but priority is deterministic)", () => {
    const env: AskResponse = {
      degraded: false,
      insight: { markdown: "x" },
      viewChange: { viewMode: "grid" },
      emailDraft: { summary: "y" },
    };
    expect(classifyChainEnvelope(env)).toBe("report");
  });
});

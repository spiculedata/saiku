/*
 * Unit tests for the schema-generator Svelte client store.
 *
 * Drives the store through its full lifecycle with a mocked SchemaGenClient
 * and fake timers:
 *   - start() fetches session + initial draft/suggestions.
 *   - Polling advances stage and stops at terminal states.
 *   - applyOp is optimistic and reconciles on success/failure.
 *   - rejectOp is UI-local; stop() clears timers + state.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type {
  DraftView,
  SchemaGenClient,
  StartResponse,
  StatusResponse,
  SuggestionOp,
  SuggestionView,
} from "$lib/api/schemaGen";
import { createSchemaGenStore } from "./schemaGen.svelte";

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (err: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (err: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function mockClient(): {
  client: SchemaGenClient;
  start: ReturnType<typeof vi.fn>;
  status: ReturnType<typeof vi.fn>;
  draft: ReturnType<typeof vi.fn>;
  suggestions: ReturnType<typeof vi.fn>;
  applyOp: ReturnType<typeof vi.fn>;
  save: ReturnType<typeof vi.fn>;
} {
  const start = vi.fn();
  const status = vi.fn();
  const draft = vi.fn();
  const suggestions = vi.fn();
  const applyOp = vi.fn();
  const save = vi.fn();
  return {
    client: { start, status, draft, suggestions, applyOp, save },
    start,
    status,
    draft,
    suggestions,
    applyOp,
    save,
  };
}

const DRAFT_A: DraftView = {
  schemaName: "Sales",
  cubes: [],
  sharedDimensions: [],
};
const DRAFT_B: DraftView = {
  schemaName: "Sales",
  cubes: [
    { name: "Orders", factTable: null, dimensions: [], measures: [] },
  ],
  sharedDimensions: [],
};

const OP_1: SuggestionOp = {
  op: "rename",
  target: { kind: "cube", path: ["Orders"] },
  newName: "Sales Orders",
};
const OP_2: SuggestionOp = {
  op: "ignore",
  target: { kind: "column", path: ["Orders", "notes"] },
};

function suggestionsWith(ops: SuggestionOp[]): SuggestionView {
  return { ops, degraded: false };
}

function startResp(stage: StatusResponse["stage"] = "INTROSPECTING"): StartResponse {
  return { sessionId: "sess-1", dataSourceId: "ds-1", stage };
}

function statusResp(
  stage: StatusResponse["stage"],
  extra: Partial<StatusResponse> = {},
): StatusResponse {
  return {
    sessionId: "sess-1",
    stage,
    failureMessage: null,
    cubeCount: 0,
    suggestionCount: 0,
    ...extra,
  };
}

describe("createSchemaGenStore", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("start() fetches session, initial draft and suggestions", async () => {
    const m = mockClient();
    m.start.mockResolvedValue(startResp("INTROSPECTING"));
    m.status.mockResolvedValue(statusResp("INTROSPECTING"));
    m.draft.mockResolvedValue(DRAFT_A);
    m.suggestions.mockResolvedValue(suggestionsWith([OP_1, OP_2]));

    const store = createSchemaGenStore(m.client, 500);
    await store.start("ds-1");

    expect(m.start).toHaveBeenCalledWith("ds-1");
    expect(store.sessionId).toBe("sess-1");
    expect(store.stage).toBe("INTROSPECTING");
    expect(store.draft).toEqual(DRAFT_A);
    expect(store.suggestions?.ops).toHaveLength(2);

    store.stop();
  });

  it("polls status and re-fetches draft/suggestions when stage changes; stops at READY", async () => {
    const m = mockClient();
    m.start.mockResolvedValue(startResp("INTROSPECTING"));
    // Sequence: INTROSPECTING -> INFERRING -> READY
    m.status
      .mockResolvedValueOnce(statusResp("INTROSPECTING"))
      .mockResolvedValueOnce(statusResp("INFERRING"))
      .mockResolvedValueOnce(statusResp("READY"));
    m.draft
      .mockResolvedValueOnce(DRAFT_A)
      .mockResolvedValueOnce(DRAFT_A)
      .mockResolvedValueOnce(DRAFT_B);
    m.suggestions
      .mockResolvedValueOnce(suggestionsWith([OP_1]))
      .mockResolvedValueOnce(suggestionsWith([OP_1]))
      .mockResolvedValueOnce(suggestionsWith([OP_1, OP_2]));

    const store = createSchemaGenStore(m.client, 500);
    await store.start("ds-1");
    // Initial fetch already happened (call 1 of status).
    expect(store.stage).toBe("INTROSPECTING");

    // Tick 1 -> INFERRING (stage changed, draft + suggestions re-fetched).
    await vi.advanceTimersByTimeAsync(500);
    expect(store.stage).toBe("INFERRING");

    // Tick 2 -> READY (terminal, polling should stop).
    await vi.advanceTimersByTimeAsync(500);
    expect(store.stage).toBe("READY");
    expect(store.draft).toEqual(DRAFT_B);

    const statusCalls = m.status.mock.calls.length;
    await vi.advanceTimersByTimeAsync(2000);
    expect(m.status.mock.calls.length).toBe(statusCalls);
  });

  it("applyOp optimistically moves op to pendingOps then commits on success", async () => {
    const m = mockClient();
    m.start.mockResolvedValue(startResp("READY"));
    m.status.mockResolvedValue(statusResp("READY"));
    m.draft.mockResolvedValue(DRAFT_A);
    m.suggestions.mockResolvedValue(suggestionsWith([OP_1, OP_2]));

    const store = createSchemaGenStore(m.client, 500);
    await store.start("ds-1");
    expect(store.suggestions?.ops).toHaveLength(2);

    const gate = deferred<DraftView>();
    m.applyOp.mockReturnValueOnce(gate.promise);

    const pending = store.applyOp(OP_1);
    // Optimistic: OP_1 is now in pendingOps, removed from suggestions.
    expect(store.pendingOps).toEqual([OP_1]);
    expect(store.suggestions?.ops).toEqual([OP_2]);

    gate.resolve(DRAFT_B);
    await pending;

    expect(store.draft).toEqual(DRAFT_B);
    expect(store.pendingOps).toEqual([]);
    expect(store.error).toBeNull();
  });

  it("applyOp failure rolls the op back into suggestions and records error", async () => {
    const m = mockClient();
    m.start.mockResolvedValue(startResp("READY"));
    m.status.mockResolvedValue(statusResp("READY"));
    m.draft.mockResolvedValue(DRAFT_A);
    m.suggestions.mockResolvedValue(suggestionsWith([OP_1, OP_2]));

    const store = createSchemaGenStore(m.client, 500);
    await store.start("ds-1");

    const gate = deferred<DraftView>();
    m.applyOp.mockReturnValueOnce(gate.promise);

    const pending = store.applyOp(OP_1);
    expect(store.pendingOps).toEqual([OP_1]);

    gate.reject(new Error("boom"));
    await pending;

    expect(store.pendingOps).toEqual([]);
    expect(store.suggestions?.ops).toEqual([OP_1, OP_2]);
    expect(store.draft).toEqual(DRAFT_A);
    expect(store.error).toContain("boom");
  });

  it("rejectOp removes the op locally and does not call the server", async () => {
    const m = mockClient();
    m.start.mockResolvedValue(startResp("READY"));
    m.status.mockResolvedValue(statusResp("READY"));
    m.draft.mockResolvedValue(DRAFT_A);
    m.suggestions.mockResolvedValue(suggestionsWith([OP_1, OP_2]));

    const store = createSchemaGenStore(m.client, 500);
    await store.start("ds-1");

    store.rejectOp(OP_1);
    expect(store.suggestions?.ops).toEqual([OP_2]);
    expect(m.applyOp).not.toHaveBeenCalled();
  });

  it("stop() clears timers and resets state", async () => {
    const m = mockClient();
    m.start.mockResolvedValue(startResp("INTROSPECTING"));
    m.status.mockResolvedValue(statusResp("INTROSPECTING"));
    m.draft.mockResolvedValue(DRAFT_A);
    m.suggestions.mockResolvedValue(suggestionsWith([OP_1]));

    const store = createSchemaGenStore(m.client, 500);
    await store.start("ds-1");
    expect(store.sessionId).toBe("sess-1");

    store.stop();
    expect(store.sessionId).toBeNull();
    expect(store.draft).toBeNull();
    expect(store.suggestions).toBeNull();

    const statusCalls = m.status.mock.calls.length;
    await vi.advanceTimersByTimeAsync(2000);
    expect(m.status.mock.calls.length).toBe(statusCalls);
  });
});

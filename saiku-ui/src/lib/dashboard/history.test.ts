/*
 * Unit tests for the pure undo/redo history stack (issue #914). Covers:
 *   - empty stack reports canUndo / canRedo false
 *   - push then undo restores the prior snapshot and enables redo
 *   - redo re-applies the undone state
 *   - a fresh push after undo forks history (clears redo future)
 *   - the cap evicts oldest snapshots FIFO
 *   - reset clears both stacks and seeds a fresh present
 *   - no-op push (same reference) is ignored
 */

import { describe, test, expect } from "vitest";
import { HistoryStack, DEFAULT_HISTORY_CAP } from "$lib/dashboard/history";

/** Minimal stand-in document — the stack is type-agnostic. */
interface Doc {
  v: number;
}

describe("HistoryStack", () => {
  test("empty stack cannot undo or redo", () => {
    const h = new HistoryStack<Doc>();
    expect(h.canUndo).toBe(false);
    expect(h.canRedo).toBe(false);
    expect(h.undo()).toBeNull();
    expect(h.redo()).toBeNull();
  });

  test("push enables undo but not redo", () => {
    const h = new HistoryStack<Doc>();
    h.reset({ v: 0 });
    h.push({ v: 0 }, { v: 1 });
    expect(h.canUndo).toBe(true);
    expect(h.canRedo).toBe(false);
    expect(h.undoDepth).toBe(1);
  });

  test("undo restores the prior snapshot", () => {
    const h = new HistoryStack<Doc>();
    h.reset({ v: 0 });
    h.push({ v: 0 }, { v: 1 });
    const restored = h.undo();
    expect(restored).toEqual({ v: 0 });
    expect(h.canUndo).toBe(false);
    expect(h.canRedo).toBe(true);
  });

  test("redo re-applies the undone state", () => {
    const h = new HistoryStack<Doc>();
    h.reset({ v: 0 });
    h.push({ v: 0 }, { v: 1 });
    h.undo();
    const redone = h.redo();
    expect(redone).toEqual({ v: 1 });
    expect(h.canUndo).toBe(true);
    expect(h.canRedo).toBe(false);
  });

  test("multi-step undo / redo walks the full chain", () => {
    const h = new HistoryStack<Doc>();
    h.reset({ v: 0 });
    h.push({ v: 0 }, { v: 1 });
    h.push({ v: 1 }, { v: 2 });
    h.push({ v: 2 }, { v: 3 });
    expect(h.undo()).toEqual({ v: 2 });
    expect(h.undo()).toEqual({ v: 1 });
    expect(h.undo()).toEqual({ v: 0 });
    expect(h.canUndo).toBe(false);
    expect(h.redo()).toEqual({ v: 1 });
    expect(h.redo()).toEqual({ v: 2 });
    expect(h.redo()).toEqual({ v: 3 });
    expect(h.canRedo).toBe(false);
  });

  test("a fresh push after undo forks history (clears redo)", () => {
    const h = new HistoryStack<Doc>();
    h.reset({ v: 0 });
    h.push({ v: 0 }, { v: 1 });
    h.push({ v: 1 }, { v: 2 });
    h.undo(); // back to v:1, redo future = [v:2]
    expect(h.canRedo).toBe(true);
    h.push({ v: 1 }, { v: 9 }); // new branch
    expect(h.canRedo).toBe(false);
    expect(h.undo()).toEqual({ v: 1 });
  });

  test("cap evicts oldest snapshots FIFO", () => {
    const h = new HistoryStack<Doc>(3);
    h.reset({ v: 0 });
    for (let i = 0; i < 5; i++) {
      h.push({ v: i }, { v: i + 1 });
    }
    // Only the 3 most recent 'before' snapshots survive: v:2, v:3, v:4.
    expect(h.undoDepth).toBe(3);
    expect(h.undo()).toEqual({ v: 4 });
    expect(h.undo()).toEqual({ v: 3 });
    expect(h.undo()).toEqual({ v: 2 });
    expect(h.canUndo).toBe(false);
  });

  test("default cap is 50", () => {
    const h = new HistoryStack<Doc>();
    h.reset({ v: 0 });
    for (let i = 0; i < 60; i++) {
      h.push({ v: i }, { v: i + 1 });
    }
    expect(h.undoDepth).toBe(DEFAULT_HISTORY_CAP);
  });

  test("cap below 1 is clamped to 1", () => {
    const h = new HistoryStack<Doc>(0);
    h.reset({ v: 0 });
    h.push({ v: 0 }, { v: 1 });
    h.push({ v: 1 }, { v: 2 });
    expect(h.undoDepth).toBe(1);
    expect(h.undo()).toEqual({ v: 1 });
  });

  test("reset clears both stacks and seeds present", () => {
    const h = new HistoryStack<Doc>();
    h.reset({ v: 0 });
    h.push({ v: 0 }, { v: 1 });
    h.undo();
    h.reset({ v: 100 });
    expect(h.canUndo).toBe(false);
    expect(h.canRedo).toBe(false);
    expect(h.undoDepth).toBe(0);
    expect(h.redoDepth).toBe(0);
  });

  test("no-op push (same reference) is ignored", () => {
    const h = new HistoryStack<Doc>();
    const doc = { v: 0 };
    h.reset(doc);
    h.push(doc, doc);
    expect(h.canUndo).toBe(false);
  });

  test("undo without a seeded present returns null", () => {
    // push seeds present too, so this exercises pure-empty state.
    const h = new HistoryStack<Doc>();
    expect(h.undo()).toBeNull();
  });
});

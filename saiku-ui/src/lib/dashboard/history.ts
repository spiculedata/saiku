/*
 * Pure undo/redo history stack for dashboard editing (issue #914).
 *
 * Snapshot-based: callers push a deep-cloned copy of the dashboard
 * document BEFORE each structural mutation (add/remove/duplicate/move/
 * resize/rename tile, tag changes, filter-panel changes). undo() walks
 * back to the previous snapshot; redo() walks forward again.
 *
 * Pure: no DOM, no stores, no clone — the caller owns cloning (it knows
 * the document type and can use structuredClone). This module just
 * manages two bounded stacks and the cursor between them. Easy to
 * unit-test in the node vitest env.
 *
 * Model: a classic two-stack design.
 *   - `past`    holds snapshots taken before each committed mutation,
 *               oldest-first. The top of `past` is the state to restore
 *               on the next undo().
 *   - `present` is the live document as last seen by the history (set on
 *               every push/undo/redo so the inverse op has a baseline).
 *   - `future`  holds states that were undone, ready to be redone.
 *
 * push(before, after): the user committed a mutation. `before` is the
 *   document as it was prior to the change (goes onto `past`); `after`
 *   is the resulting document (becomes `present`). Any redo future is
 *   discarded — a fresh edit forks history. No-ops (before deep-equal
 *   after, by the caller's equality) should be filtered by the caller;
 *   this module additionally drops a push whose `before` is identical by
 *   reference to the current present (defensive).
 *
 * The cap bounds `past`; the oldest entries are evicted FIFO so memory
 * stays bounded on long editing sessions.
 */

export const DEFAULT_HISTORY_CAP = 50;

export class HistoryStack<T> {
	private past: T[] = [];
	private future: T[] = [];
	private present: T | null = null;
	private readonly cap: number;

	constructor(cap: number = DEFAULT_HISTORY_CAP) {
		// A cap below 1 would make undo impossible; clamp to a sane floor.
		this.cap = Math.max(1, Math.floor(cap));
	}

	/** True when there is a prior snapshot to restore. */
	get canUndo(): boolean {
		return this.past.length > 0;
	}

	/** True when an undone state can be re-applied. */
	get canRedo(): boolean {
		return this.future.length > 0;
	}

	/** Number of undo steps available (mainly for tests / debugging). */
	get undoDepth(): number {
		return this.past.length;
	}

	/** Number of redo steps available (mainly for tests / debugging). */
	get redoDepth(): number {
		return this.future.length;
	}

	/** Seed the present without recording history — call once after the
	 *  initial document load / hydrate so the first push has a baseline
	 *  and the future stack starts empty. Clears any existing history. */
	reset(present: T): void {
		this.past = [];
		this.future = [];
		this.present = present;
	}

	/** Record a committed mutation. {@code before} is pushed onto the undo
	 *  stack; {@code after} becomes the live present. Forks history: any
	 *  redo future is discarded. Ignored as a no-op when {@code before}
	 *  and {@code after} are the same object reference. */
	push(before: T, after: T): void {
		if (before === after) return; // defensive no-op guard
		this.past.push(before);
		if (this.past.length > this.cap) {
			// Evict oldest — FIFO — to keep memory bounded.
			this.past.shift();
		}
		this.future = [];
		this.present = after;
	}

	/** Restore the previous snapshot. Returns the document to apply, or
	 *  null when there's nothing to undo. The current present is moved onto
	 *  the redo stack so redo() can reapply it. */
	undo(): T | null {
		if (this.past.length === 0 || this.present === null) return null;
		const restored = this.past.pop() as T;
		this.future.push(this.present);
		this.present = restored;
		return restored;
	}

	/** Re-apply the most recently undone state. Returns the document to
	 *  apply, or null when there's nothing to redo. The current present is
	 *  moved back onto the undo stack. */
	redo(): T | null {
		if (this.future.length === 0 || this.present === null) return null;
		const restored = this.future.pop() as T;
		this.past.push(this.present);
		if (this.past.length > this.cap) {
			this.past.shift();
		}
		this.present = restored;
		return restored;
	}
}

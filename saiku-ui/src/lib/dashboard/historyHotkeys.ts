/*
 * Pure keyboard predicates for dashboard undo / redo (issue #914). No DOM
 * or store access — duck-typed on the event so they're unit-tested directly
 * under the project's `node` vitest environment.
 *
 * Bindings (cross-platform):
 *   - Undo:  Ctrl+Z   (Windows/Linux)  /  Cmd+Z   (macOS)
 *   - Redo:  Ctrl+Shift+Z              /  Cmd+Shift+Z
 *            also Ctrl+Y (a common Windows redo alias) — but only when
 *            Shift is NOT held, so it never collides with undo.
 *
 * Both are ignored when focus is in a text-entry surface (input / textarea
 * / select / contenteditable) so the browser's native per-field undo keeps
 * working while the analyst is typing.
 */

import { isEditableTarget } from '$lib/dashboard/presentationHotkeys';

/** Minimal shape we read off a key event — lets tests pass plain objects. */
interface KeyLike {
	key: string;
	ctrlKey?: boolean;
	metaKey?: boolean;
	altKey?: boolean;
	shiftKey?: boolean;
	target?: EventTarget | null;
}

/** True when exactly one of Ctrl / Meta is held (the platform modifier),
 *  with no Alt. We accept either so the same handler serves Windows/Linux
 *  (Ctrl) and macOS (Cmd) without sniffing the platform. */
function hasPlatformModifier(e: KeyLike): boolean {
	if (e.altKey) return false;
	// XOR-ish: at least one of ctrl/meta, and not the odd case of neither.
	return Boolean(e.ctrlKey) || Boolean(e.metaKey);
}

/** Undo: (Ctrl|Cmd)+Z without Shift, outside a text field. */
export function isUndoKey(e: KeyLike): boolean {
	if (!hasPlatformModifier(e)) return false;
	if (e.shiftKey) return false; // Shift+Z is redo
	if (e.key !== 'z' && e.key !== 'Z') return false;
	return !isEditableTarget(e.target ?? null);
}

/** Redo: (Ctrl|Cmd)+Shift+Z, or (Ctrl|Cmd)+Y (no Shift), outside a text
 *  field. */
export function isRedoKey(e: KeyLike): boolean {
	if (!hasPlatformModifier(e)) return false;
	const z = e.key === 'z' || e.key === 'Z';
	const y = e.key === 'y' || e.key === 'Y';
	// Ctrl/Cmd+Shift+Z
	if (z && e.shiftKey) return !isEditableTarget(e.target ?? null);
	// Ctrl/Cmd+Y (Windows redo alias), no Shift
	if (y && !e.shiftKey) return !isEditableTarget(e.target ?? null);
	return false;
}

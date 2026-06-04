/*
 * Pure keyboard predicates for dashboard presentation / fullscreen mode
 * (saiku#928). No DOM or store access — duck-typed on the event so they're
 * unit-tested directly under the project's `node` vitest environment.
 */

/** Minimal shape we read off a key event — lets tests pass plain objects. */
interface KeyLike {
  key: string;
  ctrlKey?: boolean;
  metaKey?: boolean;
  altKey?: boolean;
  shiftKey?: boolean;
  target?: EventTarget | null;
}

/**
 * True when the event target is a text-entry surface, where a bare "f" is the
 * user typing — not the presentation hotkey. Duck-typed (reads `tagName` /
 * `isContentEditable`) so it works on real DOM nodes and on test stubs.
 */
export function isEditableTarget(target: EventTarget | null | undefined): boolean {
  if (!target) return false;
  const el = target as { tagName?: string; isContentEditable?: boolean };
  const tag = el.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return true;
  return el.isContentEditable === true;
}

/**
 * The "enter presentation" hotkey: a bare F (no Ctrl/Meta/Alt) pressed outside
 * a text field. Shift is allowed so Caps-Lock / Shift-F still works.
 */
export function isEnterPresentationKey(e: KeyLike): boolean {
  if (e.ctrlKey || e.metaKey || e.altKey) return false;
  if (e.key !== "f" && e.key !== "F") return false;
  return !isEditableTarget(e.target ?? null);
}

/** The exit hotkey: Escape (browser fullscreen consumes it too; this is the fallback). */
export function isExitPresentationKey(e: KeyLike): boolean {
  return e.key === "Escape" || e.key === "Esc";
}

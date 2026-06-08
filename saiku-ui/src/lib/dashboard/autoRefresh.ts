/*
 * Pure helpers for per-tile auto-refresh (issue #931).
 *
 * A tile can carry a `refreshInterval` (minutes; 0 / undefined = off) that
 * re-runs its query on that cadence. The tile components own the live
 * setInterval + visibilitychange wiring (DOM/timer side-effects); this
 * module owns only the pure bits so they can be unit-tested without a DOM
 * (vitest node env), mirroring the sibling $lib/dashboard helpers:
 *
 *   - REFRESH_INTERVAL_OPTIONS — the canonical picker list (Off / 1 / 5 /
 *     15 / 30 / 60 minutes).
 *   - normaliseInterval()      — coerce a persisted / form value into a
 *     valid option, treating anything off-list or non-positive as "off".
 *   - intervalMs()             — minutes → milliseconds (0 when off).
 *   - formatRelativeTime()     — a timestamp → a compact "2 min ago" /
 *     "just now" / "1 hr ago" string for the "Last updated" indicator.
 */

/** One entry in the auto-refresh interval picker. `minutes === 0` is the
 *  "Off" sentinel (no auto-refresh). */
export interface RefreshIntervalOption {
  /** Interval in minutes; 0 = off. */
  minutes: number;
  /** i18n key for the option label. */
  labelKey: string;
  /** English fallback label. */
  labelFallback: string;
}

/** The canonical interval list offered in the tile editor. Kept here (not
 *  inline in the modal) so the renderer and the normaliser agree on the
 *  valid set, and so it can be asserted in a unit test. */
export const REFRESH_INTERVAL_OPTIONS: readonly RefreshIntervalOption[] = [
  { minutes: 0, labelKey: "dashboard.refresh.off", labelFallback: "Off" },
  { minutes: 1, labelKey: "dashboard.refresh.every1", labelFallback: "Every 1 min" },
  { minutes: 5, labelKey: "dashboard.refresh.every5", labelFallback: "Every 5 min" },
  { minutes: 15, labelKey: "dashboard.refresh.every15", labelFallback: "Every 15 min" },
  { minutes: 30, labelKey: "dashboard.refresh.every30", labelFallback: "Every 30 min" },
  { minutes: 60, labelKey: "dashboard.refresh.every60", labelFallback: "Every 60 min" },
] as const;

/** The set of valid (non-off) minute values, derived from the option list. */
const VALID_MINUTES = new Set(
  REFRESH_INTERVAL_OPTIONS.map((o) => o.minutes).filter((m) => m > 0),
);

/**
 * Coerce a persisted or form-supplied refresh interval into a valid value.
 * Returns 0 ("off") for undefined / null / non-finite / non-positive
 * values, and for any positive number that isn't one of the offered
 * options (so a hand-edited dashboard JSON can't smuggle in a 7-second
 * refresh loop). Exact option values pass through unchanged.
 */
export function normaliseInterval(minutes: number | undefined | null): number {
  if (minutes == null || !Number.isFinite(minutes) || minutes <= 0) return 0;
  return VALID_MINUTES.has(minutes) ? minutes : 0;
}

/** Convert an interval in minutes to milliseconds; 0 when off (so callers
 *  can guard `if (ms > 0)` before arming a timer). Off-list / invalid
 *  inputs collapse to 0 via {@link normaliseInterval}. */
export function intervalMs(minutes: number | undefined | null): number {
  return normaliseInterval(minutes) * 60_000;
}

/** True when the tile's interval is a live (non-off) auto-refresh value. */
export function isAutoRefreshOn(minutes: number | undefined | null): boolean {
  return normaliseInterval(minutes) > 0;
}

/**
 * Format the elapsed time since `then` (a ms epoch timestamp) relative to
 * `now` (defaults to Date.now()) as a compact human string for the
 * "Last updated X ago" indicator:
 *
 *   < 5s          → "just now"
 *   < 60s         → "N sec ago"
 *   < 60min       → "N min ago"
 *   < 24h         → "N hr ago"
 *   otherwise     → "N day(s) ago"
 *
 * Future / equal timestamps and non-finite inputs render "just now" so the
 * indicator never shows a negative or NaN duration.
 */
export function formatRelativeTime(then: number, now: number = Date.now()): string {
  if (!Number.isFinite(then) || !Number.isFinite(now)) return "just now";
  const deltaMs = now - then;
  if (deltaMs < 5_000) return "just now";
  const sec = Math.floor(deltaMs / 1_000);
  if (sec < 60) return `${sec} sec ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min} min ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} hr ago`;
  const days = Math.floor(hr / 24);
  return days === 1 ? "1 day ago" : `${days} days ago`;
}

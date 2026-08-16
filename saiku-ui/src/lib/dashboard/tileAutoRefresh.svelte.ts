/*
 * Issue #931 — per-tile auto-refresh timer wiring (runes side).
 *
 * The pure bits (option list, interval normalisation, relative-time
 * formatting) live in $lib/dashboard/autoRefresh.ts and are unit-tested
 * there. This module owns the DOM/timer side-effects that can't run in the
 * node vitest env: a setInterval that fires a caller-supplied refresh fn on
 * the tile's cadence, paused while the tab is hidden (visibilitychange) and
 * resumed when visible, plus a 1s heartbeat that keeps the "Last updated X
 * ago" label fresh.
 *
 * Usage from a tile component:
 *
 *   const auto = new TileAutoRefresh();
 *   // inside an $effect, after a successful fetch:
 *   auto.markUpdated();
 *   // inside an $effect that reads tile.refreshInterval and the refresh fn:
 *   $effect(() => auto.arm(tile.refreshInterval, () => triggerRefresh()));
 *
 * `arm()` returns a teardown so it slots straight into an $effect: changing
 * the interval (config edit) or the tile re-rendering re-runs the effect,
 * which tears down the old timer and arms a fresh one — satisfying the
 * issue's "reset the interval on tile re-render / config change".
 */

import { intervalMs } from './autoRefresh';

export class TileAutoRefresh {
	/** Epoch ms of the last successful fetch; 0 = never. The indicator is
	 *  only shown by the tile when this is > 0. */
	lastUpdated = $state(0);
	/** Heartbeat clock (epoch ms) the indicator reads so the relative label
	 *  re-renders ~once a second without each indicator owning a timer. */
	now = $state(Date.now());

	#heartbeat: ReturnType<typeof setInterval> | null = null;
	#heartbeatRefs = 0;

	/** Record a successful fetch — stamps {@link lastUpdated} (and refreshes
	 *  {@link now} so the label immediately reads "just now"). */
	markUpdated(): void {
		const t = Date.now();
		this.lastUpdated = t;
		this.now = t;
	}

	/**
	 * Arm (or re-arm) the auto-refresh timer for the given interval (minutes;
	 * 0 / undefined / off-list = no timer). `onRefresh` is invoked on each
	 * tick AND immediately on the visibilitychange that brings a hidden tab
	 * back to visible (so a long-hidden tab catches up without waiting a full
	 * interval). The timer is suspended while the tab is hidden.
	 *
	 * Returns a teardown that clears the timer + listener; call it (or let the
	 * owning $effect call it) before re-arming so intervals never stack.
	 */
	arm(minutes: number | undefined | null, onRefresh: () => void): () => void {
		const ms = intervalMs(minutes);
		if (ms <= 0) {
			// Off — still keep a heartbeat off (nothing to tick) and no timer.
			return () => {};
		}

		let timer: ReturnType<typeof setInterval> | null = null;

		const start = (): void => {
			if (timer != null) return;
			timer = setInterval(() => {
				// Belt-and-braces: never fire while hidden even if a stray tick
				// slips through a backgrounded throttled timer.
				if (typeof document !== 'undefined' && document.hidden) return;
				onRefresh();
			}, ms);
		};
		const stop = (): void => {
			if (timer != null) {
				clearInterval(timer);
				timer = null;
			}
		};

		const onVisibility = (): void => {
			if (typeof document === 'undefined') return;
			if (document.hidden) {
				stop();
			} else {
				// Catch up immediately on resume, then resume ticking.
				onRefresh();
				start();
			}
		};

		const hiddenNow = typeof document !== 'undefined' && document.hidden;
		if (!hiddenNow) start();
		if (typeof document !== 'undefined') {
			document.addEventListener('visibilitychange', onVisibility);
		}

		return () => {
			stop();
			if (typeof document !== 'undefined') {
				document.removeEventListener('visibilitychange', onVisibility);
			}
		};
	}

	/**
	 * Start the 1s heartbeat that updates {@link now} so relative-time labels
	 * re-render. Reference-counted + paused while the tab is hidden. Returns a
	 * teardown; wire it into an $effect that runs only while the indicator is
	 * shown (auto-refresh on AND a successful fetch has happened).
	 */
	startHeartbeat(): () => void {
		this.#heartbeatRefs += 1;
		if (this.#heartbeat == null) {
			this.#heartbeat = setInterval(() => {
				if (typeof document !== 'undefined' && document.hidden) return;
				this.now = Date.now();
			}, 1_000);
		}
		return () => {
			this.#heartbeatRefs -= 1;
			if (this.#heartbeatRefs <= 0 && this.#heartbeat != null) {
				clearInterval(this.#heartbeat);
				this.#heartbeat = null;
				this.#heartbeatRefs = 0;
			}
		};
	}
}

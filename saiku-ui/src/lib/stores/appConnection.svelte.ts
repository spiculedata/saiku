/*
 * Real connection state for the App Builder's header badge.
 *
 * The badge used to be a literal: FoodMart Ops shipped `liveBadge: "Live ·
 * Saiku"` and rendered a green dot unconditionally, so it claimed "Live" with
 * the backend down. The reference mock-up it was ported from actually probes
 * and flips to an amber "Demo data" — the port kept the pixels and dropped the
 * meaning.
 *
 * Three states, each earned:
 *   "checking"  no answer yet (badge renders neutral)
 *   "live"      the session endpoint answered
 *   "demo"      answered, but the instance is in demo mode (canned data)
 *   "offline"   the probe failed — the app is showing whatever it last fetched
 *
 * Probing the session endpoint rather than adding a new one keeps this honest
 * about the thing that matters: can the browser still reach an authenticated
 * Saiku? That's exactly what fails when a container dies or a token expires.
 */

import { browser } from '$app/environment';
import { getCurrentSession } from '$lib/api/session';
import { platform } from '$lib/stores/platform.svelte';

export type ConnectionState = 'checking' | 'live' | 'demo' | 'offline';

/** Re-probe cadence. Long enough to be invisible in the network log, short
 *  enough that a dead backend surfaces while someone is still looking. */
const POLL_MS = 60_000;

class AppConnectionStore {
	state = $state<ConnectionState>('checking');

	/** Live probes in flight — the store polls only while an app is mounted. */
	#subscribers = 0;
	#timer: ReturnType<typeof setInterval> | null = null;

	/** Probe once. Never throws: a transport failure IS the answer. */
	async refresh(): Promise<void> {
		if (!browser) return;
		try {
			const s = await getCurrentSession();
			if (!s) {
				this.state = 'offline';
				return;
			}
			this.state = platform.capabilities?.demoMode ? 'demo' : 'live';
		} catch {
			this.state = 'offline';
		}
	}

	/**
	 * Start polling for as long as the caller is mounted. Returns the teardown,
	 * so a component can `$effect(() => appConnection.watch())`. Reference-counted
	 * because several surfaces may watch at once and the last one out stops the
	 * timer.
	 */
	watch(): () => void {
		this.#subscribers += 1;
		if (this.#subscribers === 1) {
			void this.refresh();
			this.#timer = setInterval(() => void this.refresh(), POLL_MS);
		}
		return () => {
			this.#subscribers -= 1;
			if (this.#subscribers === 0 && this.#timer !== null) {
				clearInterval(this.#timer);
				this.#timer = null;
			}
		};
	}
}

export const appConnection = new AppConnectionStore();

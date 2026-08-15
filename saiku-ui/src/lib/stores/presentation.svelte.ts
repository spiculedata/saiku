import { browser } from '$app/environment';

/**
 * Dashboard presentation / fullscreen mode (saiku#928).
 *
 * A chrome-free surface for meeting-room screens / TV walls: hides the app
 * topbar, the dashboard toolbar + filter bars, and flattens tile chrome, then
 * (best-effort) enters the browser's native fullscreen so even the browser UI
 * is gone. The cursor auto-hides after a few idle seconds. Esc — which the
 * browser also uses to leave fullscreen — exits the mode.
 *
 * Distinct from {@link platform.toggleFullscreen}, which only flips native
 * fullscreen and leaves all Saiku chrome on screen.
 */
class PresentationStore {
	/** True while presentation mode is engaged. Chrome reacts to this. */
	active = $state<boolean>(false);
	/** True once the pointer has been idle past the threshold — drives cursor:none. */
	cursorHidden = $state<boolean>(false);

	/** Idle delay before the cursor is hidden. */
	static readonly IDLE_MS = 3000;

	private idleTimer: ReturnType<typeof setTimeout> | null = null;
	private onPointerActivity: (() => void) | null = null;
	private onFullscreenChange: (() => void) | null = null;
	private onKeydown: ((e: KeyboardEvent) => void) | null = null;

	/** Enter presentation mode. Idempotent. */
	async enter(): Promise<void> {
		if (!browser || this.active) return;
		this.active = true;
		this.startIdleWatch();
		this.watchExit();
		// Best-effort: a denied/failed fullscreen request still leaves a usable
		// chrome-free surface, so swallow the rejection.
		try {
			await document.documentElement.requestFullscreen?.();
		} catch {
			/* fullscreen unavailable — presentation chrome-hiding still applies */
		}
	}

	/** Leave presentation mode and restore chrome. Idempotent. */
	async exit(): Promise<void> {
		if (!browser || !this.active) return;
		this.active = false;
		this.cursorHidden = false;
		this.stopIdleWatch();
		this.unwatchExit();
		try {
			if (document.fullscreenElement) await document.exitFullscreen?.();
		} catch {
			/* already out of fullscreen */
		}
	}

	toggle(): void {
		void (this.active ? this.exit() : this.enter());
	}

	/* ---------------------------- idle cursor ---------------------------- */

	private startIdleWatch(): void {
		this.onPointerActivity = () => this.bumpActivity();
		window.addEventListener('pointermove', this.onPointerActivity);
		window.addEventListener('pointerdown', this.onPointerActivity);
		this.bumpActivity();
	}

	private stopIdleWatch(): void {
		if (this.onPointerActivity) {
			window.removeEventListener('pointermove', this.onPointerActivity);
			window.removeEventListener('pointerdown', this.onPointerActivity);
			this.onPointerActivity = null;
		}
		if (this.idleTimer) {
			clearTimeout(this.idleTimer);
			this.idleTimer = null;
		}
	}

	private bumpActivity(): void {
		this.cursorHidden = false;
		if (this.idleTimer) clearTimeout(this.idleTimer);
		this.idleTimer = setTimeout(() => {
			if (this.active) this.cursorHidden = true;
		}, PresentationStore.IDLE_MS);
	}

	/* ----------------------------- exit hooks ---------------------------- */

	private watchExit(): void {
		// The browser leaves fullscreen on Esc; mirror that into presentation exit.
		this.onFullscreenChange = () => {
			if (this.active && !document.fullscreenElement) void this.exit();
		};
		document.addEventListener('fullscreenchange', this.onFullscreenChange);
		// Fallback for when fullscreen was denied (no fullscreenchange fires).
		this.onKeydown = (e: KeyboardEvent) => {
			if (e.key === 'Escape' || e.key === 'Esc') void this.exit();
		};
		window.addEventListener('keydown', this.onKeydown);
	}

	private unwatchExit(): void {
		if (this.onFullscreenChange) {
			document.removeEventListener('fullscreenchange', this.onFullscreenChange);
			this.onFullscreenChange = null;
		}
		if (this.onKeydown) {
			window.removeEventListener('keydown', this.onKeydown);
			this.onKeydown = null;
		}
	}
}

export const presentation = new PresentationStore();

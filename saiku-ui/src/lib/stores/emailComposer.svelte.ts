/*
 * One-shot signal: the AI-Ask drawer requests that the Email modal open (pre-filled
 * from the aiInsight store). The toolbar that hosts EmailMeThisModal consumes the
 * flag to flip the modal open. Kept tiny + consumable so a single request opens once.
 */

class EmailComposerStore {
	pendingOpen = $state(false);

	/** True while an email NL-draft has been requested but the composer modal
	 *  has not yet opened — drives the page-level "Preparing your email…" popup
	 *  (AiEmailPreparingPopup.svelte), rendered ABOVE/OUTSIDE the AI drawer so a
	 *  drawer remount can't kill it. Cleared when the composer opens
	 *  ({@link consumeOpen}) or on error ({@link stopPreparing}). */
	preparing = $state(false);

	/** Signal that a draft is being prepared — show the loading popup. */
	beginPreparing(): void {
		this.preparing = true;
	}

	/** Clear the preparing state without opening (e.g. the draft errored). */
	stopPreparing(): void {
		this.preparing = false;
	}

	requestOpen(): void {
		this.pendingOpen = true;
	}

	/** Returns true if an open was pending, and clears it. Also clears the
	 *  preparing flag so the loading popup hands off to the composer modal with
	 *  no overlap flash. */
	consumeOpen(): boolean {
		const was = this.pendingOpen;
		this.pendingOpen = false;
		this.preparing = false;
		return was;
	}
}

export const emailComposer = new EmailComposerStore();

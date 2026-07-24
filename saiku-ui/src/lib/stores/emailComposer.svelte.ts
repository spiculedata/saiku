/*
 * One-shot signal: the AI-Ask drawer requests that the Email modal open (pre-filled
 * from the aiInsight store). The toolbar that hosts EmailMeThisModal consumes the
 * flag to flip the modal open. Kept tiny + consumable so a single request opens once.
 */

class EmailComposerStore {
  pendingOpen = $state(false);

  requestOpen(): void {
    this.pendingOpen = true;
  }

  /** Returns true if an open was pending, and clears it. */
  consumeOpen(): boolean {
    const was = this.pendingOpen;
    this.pendingOpen = false;
    return was;
  }
}

export const emailComposer = new EmailComposerStore();

import { describe, it, expect } from 'vitest';
import { emailComposer } from './emailComposer.svelte';

describe('emailComposer', () => {
	it('starts closed and flags a requested open, consumed once', () => {
		emailComposer.consumeOpen(); // reset any prior state
		expect(emailComposer.pendingOpen).toBe(false);
		emailComposer.requestOpen();
		expect(emailComposer.pendingOpen).toBe(true);
		expect(emailComposer.consumeOpen()).toBe(true); // consuming returns true once
		expect(emailComposer.pendingOpen).toBe(false); // and clears it
		expect(emailComposer.consumeOpen()).toBe(false); // second consume is false
	});

	it('shows the preparing popup on draft-start and hides it when the composer opens', () => {
		emailComposer.stopPreparing(); // reset
		expect(emailComposer.preparing).toBe(false);
		emailComposer.beginPreparing();
		expect(emailComposer.preparing).toBe(true);
		// consuming the open (the composer modal opening) clears preparing — no
		// overlap flash between the loader popup and the modal.
		emailComposer.requestOpen();
		emailComposer.consumeOpen();
		expect(emailComposer.preparing).toBe(false);
	});

	it('keeps the preparing popup up THROUGH the wait, until the composer opens', async () => {
		emailComposer.stopPreparing(); // reset
		emailComposer.beginPreparing(); // Email mode, at send-time (before the ask)
		expect(emailComposer.preparing).toBe(true);
		await Promise.resolve(); // simulate the async AI draft wait
		expect(emailComposer.preparing).toBe(true); // still visible during the wait
		emailComposer.requestOpen();
		emailComposer.consumeOpen(); // composer opens
		expect(emailComposer.preparing).toBe(false); // handed off, no stuck popup
	});

	it('stopPreparing() clears the popup on error without opening the composer', () => {
		emailComposer.beginPreparing();
		expect(emailComposer.preparing).toBe(true);
		emailComposer.stopPreparing();
		expect(emailComposer.preparing).toBe(false);
		expect(emailComposer.pendingOpen).toBe(false);
	});
});

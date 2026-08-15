/*
 * Pure message-list logic for the App Builder assistant panel (saiku#1760).
 *
 * Lives outside the component so the greeting rules are unit-testable — the
 * bug this module fixes was invisible in the component precisely because the
 * rule was one line inside an effect that quietly never fired again.
 */

/** One turn in the assistant panel. */
export interface AssistantMessage {
	role: 'assistant' | 'user';
	text: string;
	kind?: 'greeting' | 'reply' | 'error';
}

/** Shown when an app hasn't configured a greeting of its own. */
export const DEFAULT_GREETING = 'Ask me about this dashboard in plain English.';

/**
 * Apply `greeting` to `messages`, returning the list to render.
 *
 * The panel used to seed the greeting only when the list was empty, which
 * meant an author editing the greeting in the inspector kept seeing the
 * previous text (usually the default) until a full page reload — the effect
 * re-ran on every change and the `messages.length === 0` guard made it a no-op.
 *
 * So: replace the greeting turn in place, leaving every real conversation turn
 * untouched — an author tuning the wording mid-conversation shouldn't lose the
 * thread. Returns the SAME array reference when nothing changes, so a caller
 * can assign unconditionally without churning reactive state.
 */
export function withGreeting(
	messages: readonly AssistantMessage[],
	greeting: string | undefined
): AssistantMessage[] {
	const text = greeting ?? DEFAULT_GREETING;
	const idx = messages.findIndex((m) => m.kind === 'greeting');
	if (idx === -1) {
		// No greeting turn yet — it opens the conversation.
		return [{ role: 'assistant', text, kind: 'greeting' }, ...messages];
	}
	if (messages[idx].text === text) return messages as AssistantMessage[];
	const next = [...messages];
	next[idx] = { ...next[idx], text };
	return next;
}

/**
 * Anthropic-shaped conversation types used by the Ask DimSum flow.
 *
 * Storing content blocks client-side in the same shape we send to and
 * receive from the tool-use API means we don't have to translate
 * between UI and API formats every turn.  Extended-thinking blocks
 * MUST be preserved on the assistant turn — Anthropic verifies the
 * signature and errors out if it's dropped.
 */

export interface AnthropicTextBlock {
	type: 'text';
	text: string;
}
export interface AnthropicToolUseBlock {
	type: 'tool_use';
	id: string;
	name: string;
	input: Record<string, unknown>;
}
export interface AnthropicToolResultBlock {
	type: 'tool_result';
	tool_use_id: string;
	content: string;
	is_error?: boolean;
}
export interface AnthropicThinkingBlock {
	type: 'thinking';
	thinking: string;
	signature: string;
}
export type AnthropicBlock =
	AnthropicTextBlock | AnthropicToolUseBlock | AnthropicToolResultBlock | AnthropicThinkingBlock;

export interface ChatMessage {
	role: 'user' | 'assistant';
	content: string | AnthropicBlock[];
	ts: number;
}

/** Summary of a completed DimSum canvas mutation, shown as the
 *  Confirm/Cancel banner inside the widget. */
export interface DimSumMutationResult {
	tables: number;
	joins: number;
	warnings: string[];
}

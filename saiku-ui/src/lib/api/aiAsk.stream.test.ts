/*
 * Tests for askAiStream — the fetch + ReadableStream SSE consumer for
 * POST /rest/saiku/api/ai/ask/chain/stream.
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { askAiStream, AiAskTransportError, type AskStreamHandlers } from './aiAsk';
import type { AskRequest } from './aiAsk';

const REQ: AskRequest = {
	question: 'how are sales trending?',
	cube: { connectionName: 'foodmart', catalog: 'FoodMart', schema: 'FoodMart', cubeName: 'Sales' }
};

/** Build a minimal ReadableStream-like object (just getReader/read, which is all the
 *  implementation calls) that yields the given byte chunks in order, then signals done. */
function streamOf(chunks: Uint8Array[]): {
	getReader: () => { read: () => Promise<{ value?: Uint8Array; done: boolean }> };
} {
	let i = 0;
	return {
		getReader() {
			return {
				read() {
					if (i < chunks.length) {
						const value = chunks[i++];
						return Promise.resolve({ value, done: false });
					}
					return Promise.resolve({ value: undefined, done: true });
				}
			};
		}
	};
}

function enc(s: string): Uint8Array {
	return new TextEncoder().encode(s);
}

/** Build one SSE frame's raw text: `event: X\ndata: Y\n\n`. */
function frame(event: string, dataObj: unknown): string {
	return `event: ${event}\ndata: ${JSON.stringify(dataObj)}\n\n`;
}

afterEach(() => {
	vi.restoreAllMocks();
});

describe('askAiStream', () => {
	it('dispatches events in order with correct payloads', async () => {
		const calls: Array<[string, unknown]> = [];
		const handlers: AskStreamHandlers = {
			onModel: (m) => calls.push(['model', m]),
			onIntent: (k, i) => calls.push(['intent', { k, i }]),
			onChunk: (d) => calls.push(['chunk', d]),
			onStep: (e) => calls.push(['step', e]),
			onFinal: (e) => calls.push(['final', e]),
			onError: (r) => calls.push(['error', r]),
			onNote: (r) => calls.push(['note', r])
		};

		const stepEnvelope = { degraded: false, request: { foo: 'bar' } };
		const finalEnvelope = { degraded: false, insight: { markdown: 'Hello world' } };

		const text =
			frame('model', { model: 'claude-sonnet-4-6' }) +
			frame('intent', { kind: 'QUERY', index: 0 }) +
			frame('step', stepEnvelope) +
			frame('intent', { kind: 'INSIGHT', index: 1 }) +
			frame('chunk', { delta: 'Hello ' }) +
			frame('chunk', { delta: 'world' }) +
			frame('final', finalEnvelope) +
			frame('note', { reason: 'Reached the step limit' });

		const body = streamOf([enc(text)]);
		global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, body });

		await askAiStream(REQ, handlers);

		expect(calls).toEqual([
			['model', 'claude-sonnet-4-6'],
			['intent', { k: 'QUERY', i: 0 }],
			['step', stepEnvelope],
			['intent', { k: 'INSIGHT', i: 1 }],
			['chunk', 'Hello '],
			['chunk', 'world'],
			['final', finalEnvelope],
			['note', 'Reached the step limit']
		]);
	});

	it('reassembles a frame whose bytes are split mid data: line across chunks', async () => {
		const calls: unknown[] = [];
		const handlers: AskStreamHandlers = {
			onFinal: (e) => calls.push(e)
		};

		const finalEnvelope = { degraded: false, insight: { markdown: 'split test' } };
		const full = frame('final', finalEnvelope);
		// Split mid `data:` line — roughly halfway through the payload text.
		const splitPoint = full.indexOf('"markdown"') + 3;
		const part1 = full.slice(0, splitPoint);
		const part2 = full.slice(splitPoint);

		const body = streamOf([enc(part1), enc(part2)]);
		global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, body });

		await askAiStream(REQ, handlers);

		expect(calls).toEqual([finalEnvelope]);
	});

	it('throws AiAskTransportError with the server reason on a non-OK response', async () => {
		global.fetch = vi.fn().mockResolvedValue({
			ok: false,
			status: 503,
			body: null,
			json: () => Promise.resolve({ reason: 'not configured' })
		});

		await expect(askAiStream(REQ, {})).rejects.toMatchObject({
			name: 'AiAskTransportError',
			status: 503,
			message: 'not configured'
		});
	});

	it('throws AiAskTransportError with status 0 when fetch rejects', async () => {
		global.fetch = vi.fn().mockRejectedValue(new Error('network down'));

		let caught: unknown;
		try {
			await askAiStream(REQ, {});
		} catch (e) {
			caught = e;
		}
		expect(caught).toBeInstanceOf(AiAskTransportError);
		expect((caught as AiAskTransportError).status).toBe(0);
	});
});

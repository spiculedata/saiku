import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { FRAME_BOOT_TYPE, FRAME_LOAD_TYPE } from './pluginBridge';

/**
 * Behavioural tests for the plugin frame bootstrap, saiku-ui/static/plugin-frame.html
 * (saiku#2440). The inline script is run against a fake window/document, so each of its
 * guards (framed only, sender is window.parent, sender origin is the frame URL's origin, one
 * document only) is pinned by what it DOES, not by how its source text reads.
 */

const BOOTSTRAP = readFileSync(
	fileURLToPath(new URL('../../../../static/plugin-frame.html', import.meta.url)),
	'utf8'
);
const SCRIPT = (() => {
	const scripts = [...BOOTSTRAP.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
	expect(scripts).toHaveLength(1);
	return scripts[0];
})();

const FRAME_URL = 'http://saiku.example:8080/ui/plugin-frame.html';
const SAIKU_ORIGIN = 'http://saiku.example:8080';

type Listener = (ev: { source: unknown; origin: string; data: unknown }) => void;

interface Harness {
	win: Record<string, unknown>;
	parent: { posted: Array<{ data: unknown; target: string }> };
	written: string[];
	listeners: Listener[];
	dispatch(ev: { source: unknown; origin: string; data: unknown }): void;
}

/** Run the bootstrap. `framed: false` makes window.parent the window itself (top-level). */
function boot({ framed = true }: { framed?: boolean } = {}): Harness {
	const parent = {
		posted: [] as Array<{ data: unknown; target: string }>,
		postMessage(data: unknown, target: string) {
			parent.posted.push({ data, target });
		}
	};
	const written: string[] = [];
	const listeners: Listener[] = [];
	const win: Record<string, unknown> = {
		location: { href: FRAME_URL },
		addEventListener(type: string, fn: Listener) {
			if (type === 'message') listeners.push(fn);
		}
	};
	win.parent = framed ? parent : win;
	const document = {
		open() {
			written.push('<open>');
		},
		write(html: string) {
			written.push(html);
		},
		close() {
			written.push('<close>');
		}
	};
	new Function('window', 'document', SCRIPT)(win, document);
	return {
		win,
		parent,
		written,
		listeners,
		dispatch(ev) {
			for (const l of [...listeners]) l(ev);
		}
	};
}

const LOAD = { type: FRAME_LOAD_TYPE, html: '<p id="plugin">ok</p>' };

describe('plugin-frame.html bootstrap — saiku#2440', () => {
	it('posts one boot message to its parent, addressed to the Saiku origin only', () => {
		const h = boot();
		expect(h.parent.posted).toEqual([{ data: { type: FRAME_BOOT_TYPE }, target: SAIKU_ORIGIN }]);
	});

	it('writes the document sent by its parent from the Saiku origin (positive control)', () => {
		const h = boot();
		h.dispatch({ source: h.parent, origin: SAIKU_ORIGIN, data: LOAD });
		expect(h.written).toEqual(['<open>', LOAD.html, '<close>']);
	});

	it('ignores a load message from any window other than its parent', () => {
		const h = boot();
		const sibling = { postMessage() {} };
		h.dispatch({ source: sibling, origin: SAIKU_ORIGIN, data: LOAD });
		h.dispatch({ source: h.win, origin: SAIKU_ORIGIN, data: LOAD });
		h.dispatch({ source: null, origin: SAIKU_ORIGIN, data: LOAD });
		expect(h.written).toEqual([]);
	});

	it('ignores a load message from its parent when the parent is not the Saiku origin', () => {
		const h = boot();
		for (const origin of [
			'null',
			'http://evil.example',
			'http://saiku.example:8081',
			'http://saiku.example:80801',
			'http://saiku.example:8080.evil',
			'https://saiku.example.evil',
			'http://saiku.example',
			'https://saiku.example:8080',
			''
		]) {
			h.dispatch({ source: h.parent, origin, data: LOAD });
		}
		expect(h.written).toEqual([]);
	});

	it('ignores malformed load messages', () => {
		const h = boot();
		for (const data of [
			null,
			'x',
			{},
			{ type: FRAME_LOAD_TYPE },
			{ type: FRAME_LOAD_TYPE, html: 42 },
			{ type: 'other', html: 'x' }
		]) {
			h.dispatch({ source: h.parent, origin: SAIKU_ORIGIN, data });
		}
		expect(h.written).toEqual([]);
	});

	it('accepts exactly one document', () => {
		const h = boot();
		h.dispatch({ source: h.parent, origin: SAIKU_ORIGIN, data: LOAD });
		h.dispatch({
			source: h.parent,
			origin: SAIKU_ORIGIN,
			data: { type: FRAME_LOAD_TYPE, html: '<p>second</p>' }
		});
		expect(h.written).toEqual(['<open>', LOAD.html, '<close>']);
	});

	it('does nothing at all when it is not framed', () => {
		const h = boot({ framed: false });
		expect(h.listeners).toHaveLength(0);
		// Even a message that would pass the other checks (from "the parent", which is itself,
		// at the Saiku origin, as an unsandboxed top-level copy would see) writes nothing.
		h.dispatch({ source: h.win, origin: SAIKU_ORIGIN, data: LOAD });
		expect(h.written).toEqual([]);
	});

	it('fetches nothing and carries no plugin code', () => {
		expect(SCRIPT).not.toMatch(/fetch\(|XMLHttpRequest|import\(|eval\(|new Function/);
	});
});

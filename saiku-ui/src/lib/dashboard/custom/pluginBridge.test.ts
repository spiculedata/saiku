import { describe, expect, it, test } from 'vitest';
import fc from 'fast-check';
import {
	handlePluginMessage,
	buildSrcdoc,
	validatePluginOptions,
	PLUGIN_MIN_H,
	PLUGIN_MAX_H,
	PLUGIN_CSP,
	PLUGIN_FRAME_CSP,
	PLUGIN_FRAME_PATH,
	FRAME_BOOT_TYPE,
	FRAME_LOAD_TYPE,
	isFrameBootMessage,
	buildFrameLoadMessage,
	routeFrameMessage
} from './pluginBridge';

const NONCE = 'test-nonce-abc123';

describe('handlePluginMessage — authentication', () => {
	it('drops a message whose nonce does not match', () => {
		expect(handlePluginMessage({ type: 'ready', nonce: 'wrong' }, NONCE)).toBeNull();
	});

	it('drops a message with no nonce', () => {
		expect(handlePluginMessage({ type: 'ready' }, NONCE)).toBeNull();
	});

	it('accepts a matching-nonce ready', () => {
		expect(handlePluginMessage({ type: 'ready', nonce: NONCE }, NONCE)).toEqual({ kind: 'ready' });
	});
});

describe('handlePluginMessage — non-object / junk', () => {
	it.each([null, undefined, 42, 'string', true, Symbol('x')])(
		'returns null for non-object input %s',
		(v) => {
			expect(handlePluginMessage(v as unknown, NONCE)).toBeNull();
		}
	);

	it('returns null for an unknown type', () => {
		expect(handlePluginMessage({ type: 'explode', nonce: NONCE }, NONCE)).toBeNull();
	});

	it('returns null for an absent type', () => {
		expect(handlePluginMessage({ nonce: NONCE }, NONCE)).toBeNull();
	});
});

describe('handlePluginMessage — resize', () => {
	it('passes a normal height through', () => {
		expect(handlePluginMessage({ type: 'resize', nonce: NONCE, height: 300 }, NONCE)).toEqual({
			kind: 'resize',
			height: 300
		});
	});

	it('clamps below MIN up to MIN', () => {
		expect(handlePluginMessage({ type: 'resize', nonce: NONCE, height: 1 }, NONCE)).toEqual({
			kind: 'resize',
			height: PLUGIN_MIN_H
		});
	});

	it('clamps above MAX down to MAX', () => {
		expect(handlePluginMessage({ type: 'resize', nonce: NONCE, height: 999999 }, NONCE)).toEqual({
			kind: 'resize',
			height: PLUGIN_MAX_H
		});
	});

	it('rounds fractional heights', () => {
		expect(handlePluginMessage({ type: 'resize', nonce: NONCE, height: 300.7 }, NONCE)).toEqual({
			kind: 'resize',
			height: 301
		});
	});

	it('returns null for NaN height', () => {
		expect(handlePluginMessage({ type: 'resize', nonce: NONCE, height: NaN }, NONCE)).toBeNull();
	});

	it('returns null for Infinity height', () => {
		expect(
			handlePluginMessage({ type: 'resize', nonce: NONCE, height: Infinity }, NONCE)
		).toBeNull();
	});

	it('returns null for a non-numeric height string', () => {
		expect(handlePluginMessage({ type: 'resize', nonce: NONCE, height: 'tall' }, NONCE)).toBeNull();
	});
});

describe('handlePluginMessage — filter', () => {
	it('accepts a well-formed filter', () => {
		const r = handlePluginMessage(
			{
				type: 'filter',
				nonce: NONCE,
				dimension: 'Product',
				hierarchy: 'Product',
				level: 'Category',
				members: ['Beer', 'Wine']
			},
			NONCE
		);
		expect(r).toEqual({
			kind: 'filter',
			sel: {
				dimension: 'Product',
				hierarchy: 'Product',
				level: 'Category',
				members: ['Beer', 'Wine']
			}
		});
	});

	it.each(['dimension', 'hierarchy', 'level'])('returns null when %s is not a string', (field) => {
		const base: Record<string, unknown> = {
			type: 'filter',
			nonce: NONCE,
			dimension: 'D',
			hierarchy: 'H',
			level: 'L',
			members: []
		};
		base[field] = 123;
		expect(handlePluginMessage(base, NONCE)).toBeNull();
	});

	it('filters out non-string members', () => {
		const r = handlePluginMessage(
			{
				type: 'filter',
				nonce: NONCE,
				dimension: 'D',
				hierarchy: 'H',
				level: 'L',
				members: ['a', 1, null, 'b', {}, 'c']
			},
			NONCE
		);
		expect(r).toEqual({
			kind: 'filter',
			sel: { dimension: 'D', hierarchy: 'H', level: 'L', members: ['a', 'b', 'c'] }
		});
	});

	it('caps members at 500', () => {
		const members = Array.from({ length: 600 }, (_, i) => `m${i}`);
		const r = handlePluginMessage(
			{ type: 'filter', nonce: NONCE, dimension: 'D', hierarchy: 'H', level: 'L', members },
			NONCE
		);
		expect(r?.kind).toBe('filter');
		if (r?.kind === 'filter') expect(r.sel.members).toHaveLength(500);
	});

	it('treats a non-array members as empty', () => {
		const r = handlePluginMessage(
			{ type: 'filter', nonce: NONCE, dimension: 'D', hierarchy: 'H', level: 'L', members: 'oops' },
			NONCE
		);
		expect(r).toEqual({
			kind: 'filter',
			sel: { dimension: 'D', hierarchy: 'H', level: 'L', members: [] }
		});
	});
});

describe('handlePluginMessage — error', () => {
	it('passes a short message through', () => {
		expect(handlePluginMessage({ type: 'error', nonce: NONCE, message: 'boom' }, NONCE)).toEqual({
			kind: 'error',
			message: 'boom'
		});
	});

	it('caps the message at 500 chars', () => {
		const long = 'x'.repeat(2000);
		const r = handlePluginMessage({ type: 'error', nonce: NONCE, message: long }, NONCE);
		expect(r?.kind).toBe('error');
		if (r?.kind === 'error') expect(r.message).toHaveLength(500);
	});

	it('coerces a non-string message safely', () => {
		expect(handlePluginMessage({ type: 'error', nonce: NONCE, message: 42 }, NONCE)).toEqual({
			kind: 'error',
			message: '42'
		});
	});

	it('handles an absent message', () => {
		expect(handlePluginMessage({ type: 'error', nonce: NONCE }, NONCE)).toEqual({
			kind: 'error',
			message: ''
		});
	});
});

describe('buildSrcdoc', () => {
	it('puts the CSP meta first in <head>', () => {
		const doc = buildSrcdoc('<div>hi</div>', NONCE);
		const headIdx = doc.indexOf('<head>');
		const cspIdx = doc.indexOf('http-equiv="Content-Security-Policy"');
		expect(cspIdx).toBeGreaterThan(headIdx);
		// CSP must precede the charset meta and the nonce script.
		expect(cspIdx).toBeLessThan(doc.indexOf('charset'));
		expect(cspIdx).toBeLessThan(doc.indexOf('SAIKU_PLUGIN_NONCE'));
		expect(doc).toContain(PLUGIN_CSP);
	});

	it('embeds the plugin html verbatim in the body', () => {
		const doc = buildSrcdoc('<p id=x>author</p>', NONCE);
		expect(doc).toContain('<p id=x>author</p>');
	});

	it('exposes the nonce as a JS const', () => {
		const doc = buildSrcdoc('', NONCE);
		expect(doc).toContain(`window.SAIKU_PLUGIN_NONCE=${JSON.stringify(NONCE)}`);
	});

	it('strips unsafe characters from the injected nonce (no script breakout)', () => {
		const doc = buildSrcdoc('', '</script><script>alert(1)//');
		// The dangerous characters are stripped before injection.
		expect(doc).not.toContain('<script>alert(1)');
		expect(doc).toContain('window.SAIKU_PLUGIN_NONCE="scriptscriptalert1"');
	});

	it('never grants connect-src or default-src beyond the locked set', () => {
		const doc = buildSrcdoc('', NONCE);
		expect(doc).toContain("connect-src 'none'");
		expect(doc).toContain("default-src 'none'");
		expect(doc).not.toContain('allow-same-origin');
	});
});

describe('plugin frame bootstrap — saiku#2440', () => {
	const directives = (csp: string) =>
		new Map(
			csp
				.split(';')
				.map((d) => d.trim())
				.filter(Boolean)
				.map((d) => {
					const [name, ...rest] = d.split(/\s+/);
					return [name, rest.join(' ')] as const;
				})
		);

	it('is a relative static asset, not a route or an endpoint', () => {
		expect(PLUGIN_FRAME_PATH).toBe('plugin-frame.html');
	});

	it('locks the frame response down: sandboxed scripts only, nothing remote', () => {
		const d = directives(PLUGIN_FRAME_CSP);
		expect(d.get('sandbox')).toBe('allow-scripts');
		expect(d.get('default-src')).toBe("'none'");
		expect(d.get('script-src')).toBe("'unsafe-inline'");
		expect(d.get('connect-src')).toBe("'none'");
		expect(d.get('base-uri')).toBe("'none'");
		expect(d.get('form-action')).toBe("'none'");
		expect(PLUGIN_FRAME_CSP).not.toContain('allow-same-origin');
	});

	it('keeps every directive of the plugin document policy', () => {
		const frame = directives(PLUGIN_FRAME_CSP);
		for (const [name, value] of directives(PLUGIN_CSP)) expect(frame.get(name)).toBe(value);
	});

	it('accepts the boot message only from an opaque (sandboxed) origin', () => {
		expect(isFrameBootMessage({ type: FRAME_BOOT_TYPE }, 'null')).toBe(true);
		// A same-origin (unsandboxed) frame would report the Saiku origin: never deliver to it.
		expect(isFrameBootMessage({ type: FRAME_BOOT_TYPE }, 'http://127.0.0.1:8080')).toBe(false);
		expect(isFrameBootMessage({ type: FRAME_BOOT_TYPE }, '')).toBe(false);
	});

	it.each([
		null,
		undefined,
		1,
		'saiku-plugin-frame-boot',
		{},
		{ type: 'ready' },
		{ type: FRAME_LOAD_TYPE }
	])('rejects %s as a boot message', (raw) => {
		expect(isFrameBootMessage(raw as unknown, 'null')).toBe(false);
	});

	it('posts the strict-CSP plugin document built from the registry html', () => {
		const msg = buildFrameLoadMessage('<p id=x>author</p>', NONCE);
		expect(msg.type).toBe(FRAME_LOAD_TYPE);
		expect(msg.html).toBe(buildSrcdoc('<p id=x>author</p>', NONCE));
		expect(msg.html).toContain(PLUGIN_CSP);
		expect(msg.html).toContain(`window.SAIKU_PLUGIN_NONCE=${JSON.stringify(NONCE)}`);
	});

	test('isFrameBootMessage never throws on arbitrary input', () => {
		fc.assert(
			fc.property(fc.anything(), fc.string(), (raw, origin) => {
				expect(typeof isFrameBootMessage(raw, origin)).toBe('boolean');
			})
		);
	});
});

describe('routeFrameMessage — the host guards (saiku#2440)', () => {
	const tileWindow = { name: 'this tile' };
	const otherWindow = { name: 'another frame' };
	const frame = { window: tileWindow, nonce: NONCE, pluginHtml: '<p>plugin</p>' };
	const boot = { type: FRAME_BOOT_TYPE };

	it('answers its own sandboxed frame boot with the plugin document', () => {
		expect(routeFrameMessage({ source: tileWindow, origin: 'null', data: boot }, frame)).toEqual({
			kind: 'deliver',
			message: buildFrameLoadMessage('<p>plugin</p>', NONCE)
		});
	});

	it('ignores a boot from any other window, even an opaque one', () => {
		expect(
			routeFrameMessage({ source: otherWindow, origin: 'null', data: boot }, frame)
		).toBeNull();
		expect(routeFrameMessage({ source: null, origin: 'null', data: boot }, frame)).toBeNull();
	});

	it('never delivers to its own frame when that frame is not at an opaque origin', () => {
		for (const origin of ['http://127.0.0.1:8080', 'https://saiku.example', '']) {
			expect(routeFrameMessage({ source: tileWindow, origin, data: boot }, frame)).toBeNull();
		}
	});

	it('delivers nothing before the registry html is loaded, or without a frame', () => {
		expect(
			routeFrameMessage(
				{ source: tileWindow, origin: 'null', data: boot },
				{ ...frame, pluginHtml: null }
			)
		).toBeNull();
		expect(
			routeFrameMessage(
				{ source: undefined, origin: 'null', data: boot },
				{ ...frame, window: null }
			)
		).toBeNull();
	});

	it('passes a nonce-authenticated plugin message from its own frame only', () => {
		const ready = { type: 'ready', nonce: NONCE };
		expect(routeFrameMessage({ source: tileWindow, origin: 'null', data: ready }, frame)).toEqual({
			kind: 'plugin',
			msg: { kind: 'ready' }
		});
		expect(
			routeFrameMessage({ source: otherWindow, origin: 'null', data: ready }, frame)
		).toBeNull();
		expect(
			routeFrameMessage(
				{ source: tileWindow, origin: 'null', data: { type: 'ready', nonce: 'x' } },
				frame
			)
		).toBeNull();
	});
});

describe('validatePluginOptions — saiku#1441 (no arbitrary author HTML)', () => {
	it('accepts a valid pluginId slug', () => {
		expect(validatePluginOptions({ pluginId: 'records-bars' })).toEqual({
			ok: true,
			value: { pluginId: 'records-bars' }
		});
	});

	it('accepts null/undefined as empty options', () => {
		expect(validatePluginOptions(null)).toEqual({ ok: true, value: {} });
		expect(validatePluginOptions(undefined)).toEqual({ ok: true, value: {} });
	});

	it('REJECTS a legacy inline html field outright', () => {
		const r = validatePluginOptions({ html: '<script>alert(1)</script>' });
		expect(r.ok).toBe(false);
	});

	it('rejects html even when a pluginId is also present (no back door)', () => {
		const r = validatePluginOptions({ pluginId: 'records-bars', html: '<div>x</div>' });
		expect(r.ok).toBe(false);
	});

	it.each(['Bad_Id', '../etc', 'a/b', 'UPPER', 'has space', '-leading', ''])(
		'rejects a slug-invalid pluginId %s',
		(id) => {
			expect(validatePluginOptions({ pluginId: id }).ok).toBe(false);
		}
	);

	it('passes through extra author config alongside pluginId', () => {
		const r = validatePluginOptions({ pluginId: 'records-bars', color: 'blue' });
		expect(r).toEqual({ ok: true, value: { pluginId: 'records-bars', color: 'blue' } });
	});

	it('rejects a non-object options blob', () => {
		expect(validatePluginOptions('nope').ok).toBe(false);
	});
});

// ─────────────────────────── property tests ───────────────────────────
// ~500 runs each. The security-critical invariants must hold for ANY input.

const anyMessage = fc.anything();

describe('handlePluginMessage — properties', () => {
	test('wrong nonce ALWAYS yields null', () => {
		fc.assert(
			fc.property(fc.dictionary(fc.string(), fc.anything()), fc.string(), (obj, wrong) => {
				// Force a mismatching nonce.
				const msg = { ...obj, nonce: wrong };
				return handlePluginMessage(msg, `${wrong}::different`) === null;
			}),
			{ numRuns: 500 }
		);
	});

	test('a returned resize height is ALWAYS within [MIN, MAX]', () => {
		fc.assert(
			fc.property(fc.anything(), (height) => {
				const r = handlePluginMessage({ type: 'resize', nonce: NONCE, height }, NONCE);
				if (r && r.kind === 'resize') {
					return r.height >= PLUGIN_MIN_H && r.height <= PLUGIN_MAX_H;
				}
				return true;
			}),
			{ numRuns: 500 }
		);
	});

	test('handlePluginMessage NEVER throws for ANY input', () => {
		fc.assert(
			fc.property(anyMessage, fc.string(), (raw, nonce) => {
				// Must not throw regardless of shape.
				handlePluginMessage(raw, nonce);
				return true;
			}),
			{ numRuns: 500 }
		);
	});

	test('a returned filter NEVER carries more than 500 members, all strings', () => {
		fc.assert(
			fc.property(fc.array(fc.anything()), (members) => {
				const r = handlePluginMessage(
					{ type: 'filter', nonce: NONCE, dimension: 'D', hierarchy: 'H', level: 'L', members },
					NONCE
				);
				if (r && r.kind === 'filter') {
					return r.sel.members.length <= 500 && r.sel.members.every((x) => typeof x === 'string');
				}
				return true;
			}),
			{ numRuns: 500 }
		);
	});
});

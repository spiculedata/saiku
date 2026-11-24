import { describe, it, expect } from 'vitest';
import { bufferKeyFor, type IdeSelection } from './bufferKey';

describe('bufferKeyFor', () => {
	it('keys a Mondrian schema under the xml: prefix — the prefix the load/save sites write under', () => {
		const sel: IdeSelection = { kind: 'mondrian', schema: { name: 'Foodmart' } };
		expect(bufferKeyFor(sel)).toBe('xml:Foodmart');
	});

	it('keys an Ossie model under the ossie: prefix', () => {
		const sel: IdeSelection = { kind: 'ossie', datasource: { name: 'ossie-ds' } };
		expect(bufferKeyFor(sel)).toBe('ossie:ossie-ds');
	});

	it('round-trips: the key a load site writes is the key currentContent reads and saveMondrian saves', () => {
		// Regression for the `${kind}:${name}` spelling, which produced `mondrian:Foodmart`
		// while selectSchema stored `xml:Foodmart` — the editor opened empty and saves
		// uploaded the untouched server copy.
		const buffers: Record<string, string> = {};
		const sel: IdeSelection = { kind: 'mondrian', schema: { name: 'Foodmart' } };
		const key = bufferKeyFor(sel);
		buffers[key] = '<Schema name="Foodmart"/>';
		expect(buffers[bufferKeyFor(sel)]).toBe('<Schema name="Foodmart"/>');
	});
});

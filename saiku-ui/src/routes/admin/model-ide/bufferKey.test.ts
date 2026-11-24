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
});

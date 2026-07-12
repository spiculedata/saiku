import { describe, it, expect } from 'vitest';
import { riskBand, jurisdictionFlag } from '../src/lib/format';

describe('riskBand', () => {
	it('maps score to a band + colour var', () => {
		expect(riskBand(0.85)).toEqual({ label: 'High', color: 'var(--red)' });
		expect(riskBand(0.5)).toEqual({ label: 'Medium', color: 'var(--amber)' });
		expect(riskBand(0.1)).toEqual({ label: 'Low', color: 'var(--green)' });
		expect(riskBand(null)).toEqual({ label: 'Unknown', color: 'var(--dim)' });
	});
});

describe('jurisdictionFlag', () => {
	it('returns an emoji flag for known ISO codes, globe otherwise', () => {
		expect(jurisdictionFlag('GB')).toBe('🇬🇧');
		expect(jurisdictionFlag('no')).toBe('🇳🇴');
		expect(jurisdictionFlag('ZZ')).toBe('🌐');
		expect(jurisdictionFlag(null)).toBe('🌐');
	});
});

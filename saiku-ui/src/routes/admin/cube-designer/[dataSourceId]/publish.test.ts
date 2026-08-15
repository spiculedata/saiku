import { describe, it, expect } from 'vitest';
import { buildLaunchUrl, repositorySchemaPath, resolveSchemaName } from './publish';

describe('repositorySchemaPath', () => {
	// Must stay in step with AdminResource.uploadSchema, which derives the path from the
	// `name` form field alone and accepts no path of its own.
	it('matches the path the schema upload endpoint writes to', () => {
		expect(repositorySchemaPath('sales-cube')).toBe('/datasources/sales-cube.xml');
	});
});

describe('resolveSchemaName (saiku#1861)', () => {
	it('uses the designer label when one was set', () => {
		expect(resolveSchemaName('Q3 Revenue', 'warehouse')).toBe('Q3 Revenue');
	});

	it('trims a padded label rather than saving the padding', () => {
		expect(resolveSchemaName('  Q3 Revenue  ', 'warehouse')).toBe('Q3 Revenue');
	});

	// The bug: an unset label let the export default 'Untitled' become the catalog name,
	// while the file was saved under a different name entirely.
	it('falls back to a datasource-derived name, never Untitled', () => {
		expect(resolveSchemaName('', 'warehouse')).toBe('warehouse-cube');
		expect(resolveSchemaName('   ', 'warehouse')).toBe('warehouse-cube');
		expect(resolveSchemaName(null, 'warehouse')).toBe('warehouse-cube');
		expect(resolveSchemaName(undefined, 'warehouse')).toBe('warehouse-cube');
	});
});

describe('buildLaunchUrl (saiku#1859)', () => {
	it('points at the OSS Studio route, not the Cloud launch route', () => {
		const url = buildLaunchUrl({ connection: 'unknown_sales', schema: 'Sales', cube: 'Orders' });

		expect(url.startsWith('/ui/?')).toBe(true);
		expect(url).not.toContain('/saiku/launch');
	});

	it('sends catalog and schema as the same Mondrian schema name', () => {
		const params = new URLSearchParams(
			buildLaunchUrl({ connection: 'unknown_sales', schema: 'Sales', cube: 'Orders' }).slice(5)
		);

		expect(params.get('starterCubeConnection')).toBe('unknown_sales');
		expect(params.get('starterCubeCatalog')).toBe('Sales');
		expect(params.get('starterCubeSchema')).toBe('Sales');
		expect(params.get('starterCubeName')).toBe('Orders');
	});

	it('escapes cube names containing spaces and separators', () => {
		const url = buildLaunchUrl({
			connection: 'unknown_e2e',
			schema: 'E2E Sales',
			cube: 'Sales & Returns'
		});
		const params = new URLSearchParams(url.slice(5));

		expect(params.get('starterCubeName')).toBe('Sales & Returns');
		expect(params.get('starterCubeSchema')).toBe('E2E Sales');
		// A raw '&' would have split the query string and silently truncated the cube name.
		expect(url).not.toContain('Sales & Returns');
	});
});

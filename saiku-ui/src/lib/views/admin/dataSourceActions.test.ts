/*
 * Unit tests for the data-source row actions helpers (D6).
 *
 * The "Generate schema" button on the datasources admin view is only shown
 * when the data source has no Mondrian schema attached. We encode that
 * decision plus the target href in pure helpers so the rule is testable
 * without mounting Svelte components.
 */

import { describe, expect, it } from 'vitest';

import {
	canGenerateSchema,
	generateSchemaHref,
	generateSchemaLabel,
	supportsCubeDesigner,
	type GenerateSchemaTarget
} from './dataSourceActions';

describe('canGenerateSchema', () => {
	it('returns false when schemaName is a non-empty string', () => {
		const ds: GenerateSchemaTarget = { id: 'ds-1', schemaName: 'SteelWheels' };
		expect(canGenerateSchema(ds)).toBe(false);
	});

	it('returns true when schemaName is missing', () => {
		const ds: GenerateSchemaTarget = { id: 'ds-1' };
		expect(canGenerateSchema(ds)).toBe(true);
	});

	it('returns true when schemaName is null', () => {
		const ds: GenerateSchemaTarget = { id: 'ds-1', schemaName: null };
		expect(canGenerateSchema(ds)).toBe(true);
	});

	it('returns true when schemaName is an empty / whitespace string', () => {
		expect(canGenerateSchema({ id: 'ds-1', schemaName: '' })).toBe(true);
		expect(canGenerateSchema({ id: 'ds-1', schemaName: '   ' })).toBe(true);
	});
});

describe('generateSchemaLabel', () => {
	it("returns 'Design cube' when the data source has no schema", () => {
		expect(generateSchemaLabel({ id: 'ds-1' })).toBe('Design cube');
		expect(generateSchemaLabel({ id: 'ds-1', schemaName: null })).toBe('Design cube');
		expect(generateSchemaLabel({ id: 'ds-1', schemaName: '' })).toBe('Design cube');
		expect(generateSchemaLabel({ id: 'ds-1', schemaName: '   ' })).toBe('Design cube');
	});

	it("returns 'Edit cube schema' when a schema is attached", () => {
		expect(generateSchemaLabel({ id: 'ds-1', schemaName: 'SteelWheels' })).toBe('Edit cube schema');
	});
});

describe('generateSchemaHref', () => {
	it('links to the cube-designer route for the data source id', () => {
		expect(generateSchemaHref({ id: 'ds-1' })).toBe('/admin/cube-designer/ds-1');
	});

	it('percent-encodes ids that contain URL-unsafe characters', () => {
		const href = generateSchemaHref({ id: 'foo bar/baz?x' });
		expect(href).toBe('/admin/cube-designer/foo%20bar%2Fbaz%3Fx');
		// the raw id must not leak through
		expect(href).not.toContain(' ');
		expect(href).not.toContain('?');
	});

	it('uses the datasource NAME (the backend resolves by name), not the UUID id', () => {
		// Regression: passing ds.id (a UUID) 500s server-side because
		// getDatasource keys on name. The link must carry the name.
		expect(
			generateSchemaHref({ id: '4432dd20-fcae-11e3-a3ac-0800200c9a66', name: 'foodmart' })
		).toBe('/admin/cube-designer/foodmart');
	});
});

describe('supportsCubeDesigner (saiku#1841)', () => {
	it('is false for an Ossie source on the wire discriminator', () => {
		// The designer is a Mondrian XML tool; an Ossie source has no Mondrian
		// schema to load, so it opened empty and a Save would have written the
		// wrong kind of schema entirely.
		expect(supportsCubeDesigner({ id: 'ds-1', connectiontype: 'OSSIE' })).toBe(false);
	});

	it('is false for an Ossie source saved with only the legacy type field', () => {
		expect(supportsCubeDesigner({ id: 'ds-1', type: 'OSSIE' })).toBe(false);
	});

	it('is true for Mondrian and XMLA sources', () => {
		expect(supportsCubeDesigner({ id: 'ds-1', connectiontype: 'MONDRIAN' })).toBe(true);
		expect(supportsCubeDesigner({ id: 'ds-2', connectiontype: 'XMLA' })).toBe(true);
	});

	it('treats an absent discriminator as Mondrian-style, not Ossie', () => {
		// Older saved sources predate connectiontype; hiding the designer for
		// them would remove a working action.
		expect(supportsCubeDesigner({ id: 'ds-1' })).toBe(true);
	});
});

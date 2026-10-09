import { describe, expect, it } from 'vitest';
import {
	buildDatasourcePayload,
	defaultTableNameFromFileName,
	isFailed,
	isReadyToSave,
	stageLabel
} from './pageViewModel';
import type { QuickstartUploadResponse } from '$lib/api/quickstart';

describe('defaultTableNameFromFileName()', () => {
	it('strips a .csv extension', () => {
		expect(defaultTableNameFromFileName('sales.csv')).toBe('sales');
	});

	it('replaces spaces and punctuation with a single underscore, trimming the edges', () => {
		expect(defaultTableNameFromFileName('Q3 Sales (2026).csv')).toBe('Q3_Sales_2026');
	});

	it('is case-insensitive about the extension', () => {
		expect(defaultTableNameFromFileName('sales.CSV')).toBe('sales');
	});

	it('falls back to a generic name when nothing usable is left', () => {
		expect(defaultTableNameFromFileName('!!!.csv')).toBe('quickstart');
	});
});

describe('buildDatasourcePayload()', () => {
	const upload: QuickstartUploadResponse = {
		jdbcUrl: 'jdbc:h2:/home/saiku/data/quickstart/sales/quickstart',
		driver: 'org.h2.Driver',
		tableName: 'sales',
		rowCount: 42,
		columns: [{ name: 'amount', type: 'LONG' }]
	};

	it('registers a MONDRIAN datasource pointing at the uploaded H2 database', () => {
		const payload = buildDatasourcePayload(upload, 'Sales');

		expect(payload.connectiontype).toBe('MONDRIAN');
		expect(payload.location).toBe(upload.jdbcUrl);
		expect(payload.driver).toBe(upload.driver);
	});

	it('uses the same name for the connection and the schema, so the pre-set Catalog resolves', () => {
		const payload = buildDatasourcePayload(upload, 'Sales');

		expect(payload.name).toBe('Sales');
		expect(payload.schemaName).toBe('Sales');
	});

	it('leaves id blank so the server assigns one', () => {
		const payload = buildDatasourcePayload(upload, 'Sales');
		expect(payload.id).toBe('');
	});
});

describe('isReadyToSave() / isFailed()', () => {
	it('is ready only at READY', () => {
		expect(isReadyToSave('READY')).toBe(true);
		expect(isReadyToSave('INFERRING')).toBe(false);
		expect(isReadyToSave(null)).toBe(false);
	});

	it('is failed only at FAILED', () => {
		expect(isFailed('FAILED')).toBe(true);
		expect(isFailed('READY')).toBe(false);
		expect(isFailed(null)).toBe(false);
	});
});

describe('stageLabel()', () => {
	it('has a distinct label for every stage plus the null (uploading) case', () => {
		const stages = [
			null,
			'PENDING',
			'INTROSPECTING',
			'INFERRING',
			'ENRICHING',
			'READY',
			'SAVED',
			'FAILED'
		] as const;
		const labels = stages.map((s) => stageLabel(s));
		expect(new Set(labels).size).toBe(labels.length);
		for (const label of labels) {
			expect(label.length).toBeGreaterThan(0);
		}
	});
});

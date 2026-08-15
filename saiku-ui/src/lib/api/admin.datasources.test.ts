/*
 * Regression tests for the datasource wire mapping (saiku#1529).
 *
 * The bug: the admin UI posted its camelCase AdminDatasource shape verbatim
 * (`name`, `location`, `schemaName`, `type`), none of which exist on the
 * server's DataSourceMapper. Jackson's FAIL_ON_UNKNOWN_PROPERTIES tripped and
 * every create/edit failed with `/datasources -> 400`. These tests lock the
 * boundary mapping so the wire body only ever carries DataSourceMapper keys.
 */
import { describe, expect, test } from 'vitest';
import { toDatasourceWire, fromDatasourceWire, type AdminDatasource } from './admin';

const uiDatasource: AdminDatasource = {
	id: '',
	name: 'test',
	connectionName: '',
	driver: 'org.postgresql.Driver',
	location: 'jdbc:postgresql://postgres:5432/testdb',
	type: 'RELATIONAL',
	connectiontype: 'XMLA',
	username: 'testuser',
	password: 's3cret',
	schemaName: 'public',
	ossieYaml: ''
};

describe('toDatasourceWire', () => {
	test('maps UI field names onto the server DataSourceMapper contract', () => {
		const wire = toDatasourceWire(uiDatasource);
		expect(wire.connectionname).toBe('test');
		expect(wire.jdbcurl).toBe('jdbc:postgresql://postgres:5432/testdb');
		expect(wire.schema).toBe('public');
		expect(wire.connectiontype).toBe('XMLA');
		expect(wire.driver).toBe('org.postgresql.Driver');
		expect(wire.username).toBe('testuser');
		expect(wire.password).toBe('s3cret');
	});

	test('emits ONLY DataSourceMapper keys — no unknown fields that would 400', () => {
		const allowed = new Set([
			'id',
			'connectionname',
			'connectiontype',
			'jdbcurl',
			'schema',
			'driver',
			'username',
			'password',
			'ossieYaml'
		]);
		for (const key of Object.keys(toDatasourceWire(uiDatasource))) {
			expect(allowed.has(key)).toBe(true);
		}
		// The offending camelCase keys must never reach the wire.
		const wire = toDatasourceWire(uiDatasource) as Record<string, unknown>;
		expect(wire.name).toBeUndefined();
		expect(wire.location).toBeUndefined();
		expect(wire.schemaName).toBeUndefined();
		expect(wire.type).toBeUndefined();
	});

	test('omits the empty id on create so the server assigns one', () => {
		expect(toDatasourceWire(uiDatasource).id).toBeUndefined();
		expect(toDatasourceWire({ ...uiDatasource, id: 'abc' }).id).toBe('abc');
	});

	test('omits a blank password so editing without retyping keeps the stored credential', () => {
		expect(toDatasourceWire({ ...uiDatasource, password: '' }).password).toBeUndefined();
		expect(toDatasourceWire({ ...uiDatasource, password: undefined }).password).toBeUndefined();
	});
});

describe('fromDatasourceWire', () => {
	test('maps a server row back onto the UI shape (round-trip of the key fields)', () => {
		const ds = fromDatasourceWire({
			id: 'ds-1',
			connectionname: 'sales-pg',
			connectiontype: 'MONDRIAN',
			jdbcurl: 'jdbc:postgresql://db:5432/sales',
			schema: 'public',
			driver: 'org.postgresql.Driver',
			username: 'reader'
		});
		expect(ds.id).toBe('ds-1');
		expect(ds.name).toBe('sales-pg');
		expect(ds.location).toBe('jdbc:postgresql://db:5432/sales');
		expect(ds.schemaName).toBe('public');
		expect(ds.type).toBe('OLAP');
	});

	test('derives the legacy type dropdown from connectiontype', () => {
		expect(fromDatasourceWire({ connectiontype: 'MONDRIAN' }).type).toBe('OLAP');
		expect(fromDatasourceWire({ connectiontype: 'XMLA' }).type).toBe('RELATIONAL');
		expect(fromDatasourceWire({ connectiontype: 'OSSIE' }).type).toBe('OSSIE');
	});
});

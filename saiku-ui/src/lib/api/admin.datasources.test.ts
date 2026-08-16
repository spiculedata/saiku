/*
 * Regression tests for the datasource wire mapping (saiku#1529).
 *
 * The bug: the admin UI posted its camelCase AdminDatasource shape verbatim
 * (`name`, `location`, `schemaName`, `type`), none of which exist on the
 * server's DataSourceMapper. Jackson's FAIL_ON_UNKNOWN_PROPERTIES tripped and
 * every create/edit failed with `/datasources -> 400`. These tests lock the
 * boundary mapping so the wire body only ever carries DataSourceMapper keys.
 */
import { describe, expect, it, test } from 'vitest';
import {
	toDatasourceWire,
	fromDatasourceWire,
	adminDatasources,
	type AdminDatasource
} from './admin';

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

/*
 * saiku#1856 — Edit datasource threw props_invalid_value and rendered nothing.
 *
 * Svelte 5 throws that error when a `bind:` prop receives `undefined`, because undefined means
 * "fall back to the default" and a two-way binding has no default to fall back to. The Edit modal
 * binds `editing.password`, `editing.username`, `editing.driver` and `editing.location`, and
 * fromDatasourceWire() left password entirely unset — so opening ANY existing datasource for edit
 * crashed the modal, silently, with only a console exception.
 *
 * startNew() initialises every one of those to '', which is exactly why "+ Add datasource" worked
 * and "Edit" did not, on the same component.
 *
 * Verified in a browser against the merged build before fixing.
 */
describe('fromDatasourceWire — every bindable field is defined (saiku#1856)', () => {
	/** Fields the edit modal binds with `bind:value`. undefined in any of them breaks the modal. */
	const BOUND_FIELDS = ['driver', 'location', 'username', 'password'] as const;

	it('never leaves a bound field undefined, even for a sparse wire record', () => {
		// The shape the server actually returns: optional fields omitted or null.
		const sparse = {
			connectionname: 'designer_e2e',
			connectiontype: 'MONDRIAN'
		} as unknown as Parameters<typeof fromDatasourceWire>[0];

		const form = fromDatasourceWire(sparse);

		for (const field of BOUND_FIELDS) {
			expect(
				form[field],
				`${field} must not be undefined — it is bound with bind:value`
			).toBeDefined();
		}
	});

	it('never leaves a bound field undefined for a fully-populated wire record', () => {
		const full = {
			id: 'abc',
			connectionname: 'foodmart',
			connectiontype: 'MONDRIAN',
			driver: 'org.h2.Driver',
			jdbcurl: 'jdbc:h2:/data/foodmart',
			schema: 'file:/data/FoodMart4.xml',
			username: 'sa'
		} as unknown as Parameters<typeof fromDatasourceWire>[0];

		const form = fromDatasourceWire(full);

		for (const field of BOUND_FIELDS) {
			expect(form[field], `${field} must not be undefined`).toBeDefined();
		}
		// The password is WRITE_ONLY server-side, so it is never returned — but the form still
		// binds it, so it has to be an empty string rather than absent.
		expect(form.password).toBe('');
	});

	it('still carries the values the wire did supply', () => {
		const wire = {
			connectionname: 'foodmart',
			connectiontype: 'MONDRIAN',
			driver: 'org.h2.Driver',
			jdbcurl: 'jdbc:h2:/data/foodmart',
			username: 'sa'
		} as unknown as Parameters<typeof fromDatasourceWire>[0];

		const form = fromDatasourceWire(wire);

		expect(form.driver).toBe('org.h2.Driver');
		expect(form.location).toBe('jdbc:h2:/data/foodmart');
		expect(form.username).toBe('sa');
	});
});

describe('adminDatasources.refresh (saiku#1862)', () => {
	// Two independent defects that combined into a silent no-op: the client sent PUT to a
	// @GET-only endpoint (405 every time, so Admin › Datasources "Refresh" never refreshed
	// anything), and callers passed the UUID id to a path param that is resolved as the
	// connection NAME (500 when it got that far).
	function captureFetch() {
		const calls: Array<{ url: string; method: string }> = [];
		const original = globalThis.fetch;
		globalThis.fetch = (async (url: RequestInfo | URL, init?: RequestInit) => {
			calls.push({ url: String(url), method: init?.method ?? 'GET' });
			return new Response('', { status: 200 });
		}) as typeof fetch;
		return { calls, restore: () => (globalThis.fetch = original) };
	}

	it('issues a GET, because the server exposes refresh as @GET only', async () => {
		const { calls, restore } = captureFetch();
		try {
			await adminDatasources.refresh('unknown_foodmart');
		} finally {
			restore();
		}

		expect(calls).toHaveLength(1);
		expect(calls[0].method).toBe('GET');
	});

	it('addresses the datasource by name, and escapes it', async () => {
		const { calls, restore } = captureFetch();
		try {
			await adminDatasources.refresh('unknown_my ds');
		} finally {
			restore();
		}

		expect(calls[0].url).toContain('/datasources/unknown_my%20ds/refresh');
	});
});

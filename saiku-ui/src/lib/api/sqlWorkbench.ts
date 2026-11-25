/*
 * SQL workbench API client (saiku#1107 phase 1) — TypeScript bindings to
 * /rest/saiku/sql-workbench, gated server-side to ROLE_SQL_EXEC / ROLE_ADMIN.
 * Cookie-based auth (`credentials: "include"`), same as every other saiku-ui api client.
 */

const REST_BASE = '/rest/saiku/sql-workbench';
const CREDS: RequestInit = { credentials: 'include' };
const JSON_HEADERS = { 'Content-Type': 'application/json' } as const;

export interface SqlDatasource {
	name: string;
	type: string | null;
}

/** Mirrors SqlWorkbenchService.DatasourceInfo on the server (saiku#2599). The URL is already
 *  credential-redacted server-side (cf. #2547); the password itself is never sent at all. */
export interface SqlDatasourceInfo {
	name: string;
	databaseProductName: string | null;
	databaseProductVersion: string | null;
	driverName: string | null;
	driverVersion: string | null;
	catalog: string | null;
	schema: string | null;
	url: string | null;
}

/** Mirrors SqlWorkbenchService.SqlQueryResult on the server. A cell may be a string, number,
 *  boolean, or null — whatever the JDBC driver's ResultSet.getObject() returned. */
export interface SqlQueryResult {
	columns: string[];
	rows: unknown[][];
	rowCount: number;
	truncated: boolean;
	durationMs: number;
}

/** Mirrors SqlWorkbenchResource.ErrorBody / SqlWorkbenchException.Code. */
export class SqlWorkbenchApiError extends Error {
	readonly code: string;

	constructor(code: string, message: string) {
		super(message);
		this.code = code;
	}
}

export async function listDatasources(): Promise<SqlDatasource[]> {
	const res = await fetch(`${REST_BASE}/datasources`, CREDS);
	if (!res.ok) {
		throw new SqlWorkbenchApiError(
			'DATASOURCES_UNAVAILABLE',
			`Could not load datasources (${res.status})`
		);
	}
	return (await res.json()) as SqlDatasource[];
}

/** saiku#2599 — what the datasource connects to: database product, catalog, default schema
 *  (and a credential-redacted URL) read live from the driver's DatabaseMetaData. */
export async function datasourceInfo(name: string): Promise<SqlDatasourceInfo> {
	const res = await fetch(`${REST_BASE}/datasources/${encodeURIComponent(name)}/info`, CREDS);
	if (!res.ok) {
		throw new SqlWorkbenchApiError(
			'INFO_UNAVAILABLE',
			`Could not load datasource info (${res.status})`
		);
	}
	return (await res.json()) as SqlDatasourceInfo;
}

/** saiku#2599 — the datasource's default-schema tables (read-only metadata), for the
 *  workbench's collapsible table list. */
export async function listTables(name: string): Promise<string[]> {
	const res = await fetch(`${REST_BASE}/datasources/${encodeURIComponent(name)}/tables`, CREDS);
	if (!res.ok) {
		throw new SqlWorkbenchApiError(
			'TABLES_UNAVAILABLE',
			`Could not load the table list (${res.status})`
		);
	}
	return (await res.json()) as string[];
}

/** saiku#2599 — the statement the table list inserts when a table is clicked. ANSI double-quote
 *  identifier quoting; embedded quotes are escaped by doubling (the "account" vs 'account'
 *  confusion from #2598). */
export function selectAllSql(table: string): string {
	return `SELECT * FROM "${table.replace(/"/g, '""')}"`;
}

export async function runQuery(
	datasource: string,
	sql: string,
	maxRows?: number
): Promise<SqlQueryResult> {
	const res = await fetch(`${REST_BASE}/query`, {
		...CREDS,
		method: 'POST',
		headers: JSON_HEADERS,
		body: JSON.stringify({ datasource, sql, maxRows: maxRows ?? null })
	});
	const body = await res.json().catch(() => null);
	if (!res.ok) {
		const code = (body?.code as string) ?? 'ERROR';
		const message = (body?.message as string) ?? `Query failed (${res.status})`;
		throw new SqlWorkbenchApiError(code, message);
	}
	return body as SqlQueryResult;
}

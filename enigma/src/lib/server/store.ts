import { env } from '$env/dynamic/private';
import pg from 'pg';

/**
 * Write-back store — persists saved investigations and Ask threads to Postgres.
 *
 * This is the demo's write path: everything else in Enigma reads (Saiku/Ossie
 * over the read-only warehouse), but Cases and Ask threads are written here.
 * Schema (provisioned out-of-band):
 *   saved_case(id, title, subject_id, subject_name, jurisdiction, note, kind, payload, created_at)
 *   ask_thread(id, case_id, question, answer, intent, model, created_at)
 *
 * DATABASE_URL is unset in local dev → the store is "disabled" and every call is
 * a no-op / empty result, so the app runs without a database.
 */

const { Pool } = pg;

let pool: pg.Pool | null = null;
function getPool(): pg.Pool | null {
	if (!env.DATABASE_URL) return null;
	if (!pool) {
		pool = new Pool({ connectionString: env.DATABASE_URL, max: 4, idleTimeoutMillis: 30_000 });
		pool.on('error', (err) => console.error('[store] idle pg client error', err.message));
	}
	return pool;
}

/** Whether the write-back store is configured (DATABASE_URL present). */
export function storeEnabled(): boolean {
	return Boolean(env.DATABASE_URL);
}

export interface SavedCase {
	id: string;
	title: string;
	subjectId: string | null;
	subjectName: string | null;
	jurisdiction: string | null;
	note: string | null;
	kind: string;
	createdAt: string;
	threadCount?: number;
}

export interface NewCase {
	title: string;
	subjectId?: string | null;
	subjectName?: string | null;
	jurisdiction?: string | null;
	note?: string | null;
	kind?: string;
	payload?: unknown;
}

export interface AskThread {
	id: string;
	question: string;
	answer: string | null;
	intent: string | null;
	model: string | null;
	createdAt: string;
}

export interface NewThread {
	caseId?: string | null;
	question: string;
	answer?: string | null;
	intent?: string | null;
	model?: string | null;
}

function rowToCase(r: Record<string, unknown>): SavedCase {
	return {
		id: String(r.id),
		title: String(r.title),
		subjectId: (r.subject_id as string) ?? null,
		subjectName: (r.subject_name as string) ?? null,
		jurisdiction: (r.jurisdiction as string) ?? null,
		note: (r.note as string) ?? null,
		kind: String(r.kind ?? 'entity'),
		createdAt: new Date(r.created_at as string).toISOString(),
		threadCount: r.thread_count != null ? Number(r.thread_count) : undefined
	};
}

/** Insert a saved case, returning the created row. */
export async function saveCase(c: NewCase): Promise<SavedCase | null> {
	const p = getPool();
	if (!p) return null;
	const { rows } = await p.query(
		`insert into saved_case (title, subject_id, subject_name, jurisdiction, note, kind, payload)
		 values ($1, $2, $3, $4, $5, $6, $7)
		 returning id, title, subject_id, subject_name, jurisdiction, note, kind, created_at`,
		[
			c.title,
			c.subjectId ?? null,
			c.subjectName ?? null,
			c.jurisdiction ?? null,
			c.note ?? null,
			c.kind ?? 'entity',
			c.payload != null ? JSON.stringify(c.payload) : null
		]
	);
	return rowToCase(rows[0]);
}

/** List saved cases, newest first, with their Ask-thread counts. */
export async function listCases(limit = 60): Promise<SavedCase[]> {
	const p = getPool();
	if (!p) return [];
	const { rows } = await p.query(
		`select c.id, c.title, c.subject_id, c.subject_name, c.jurisdiction, c.note, c.kind, c.created_at,
		        count(t.id) as thread_count
		 from saved_case c
		 left join ask_thread t on t.case_id = c.id
		 group by c.id
		 order by c.created_at desc
		 limit $1`,
		[limit]
	);
	return rows.map(rowToCase);
}

/** Delete a saved case by id; returns true if a row was removed. */
export async function deleteCase(id: string): Promise<boolean> {
	const p = getPool();
	if (!p) return false;
	const { rowCount } = await p.query(`delete from saved_case where id = $1`, [id]);
	return (rowCount ?? 0) > 0;
}

/** Persist an Ask thread (optionally attached to a case). */
export async function saveAskThread(t: NewThread): Promise<AskThread | null> {
	const p = getPool();
	if (!p) return null;
	const { rows } = await p.query(
		`insert into ask_thread (case_id, question, answer, intent, model)
		 values ($1, $2, $3, $4, $5)
		 returning id, question, answer, intent, model, created_at`,
		[t.caseId ?? null, t.question, t.answer ?? null, t.intent ?? null, t.model ?? null]
	);
	const r = rows[0];
	return {
		id: String(r.id),
		question: String(r.question),
		answer: (r.answer as string) ?? null,
		intent: (r.intent as string) ?? null,
		model: (r.model as string) ?? null,
		createdAt: new Date(r.created_at as string).toISOString()
	};
}

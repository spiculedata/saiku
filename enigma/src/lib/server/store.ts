import { env } from '$env/dynamic/private';
import pg from 'pg';
import { CASE_STATUSES, CASE_PRIORITIES, type CaseStatus, type CasePriority } from '$lib/caseTypes';

// Re-export so existing server-side imports (`from '$lib/server/store'`) keep working.
export { CASE_STATUSES, CASE_PRIORITIES };
export type { CaseStatus, CasePriority };

/**
 * Write-back store — persists casework to Postgres.
 *
 * Everything else in Enigma reads (Saiku/Ossie over the read-only warehouse);
 * cases are the write path. Beyond a saved card, a case now carries a lifecycle
 * (status/priority/assignee) and an append-only activity timeline — the shape a
 * real compliance workbench (cf. Muninn) needs.
 *
 * Schema (provisioned out-of-band):
 *   saved_case(id, title, subject_id, subject_name, jurisdiction, note, kind,
 *              status, priority, assignee, payload, created_at)
 *   case_activity(id, case_id, kind, detail, actor, created_at)
 *   ask_thread(id, case_id, question, answer, intent, model, created_at)
 *
 * DATABASE_URL unset (local dev) → store disabled, every call is a no-op.
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
	status: CaseStatus;
	priority: CasePriority;
	assignee: string | null;
	createdAt: string;
	threadCount?: number;
	activityCount?: number;
}

export interface NewCase {
	title: string;
	subjectId?: string | null;
	subjectName?: string | null;
	jurisdiction?: string | null;
	note?: string | null;
	kind?: string;
	priority?: CasePriority;
	assignee?: string | null;
	payload?: unknown;
}

export interface CaseActivity {
	id: string;
	kind: string;
	detail: string | null;
	actor: string | null;
	createdAt: string;
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

export interface CaseDetail {
	case: SavedCase;
	activities: CaseActivity[];
	threads: AskThread[];
}

const DEFAULT_ACTOR = 'analyst';

function rowToCase(r: Record<string, unknown>): SavedCase {
	return {
		id: String(r.id),
		title: String(r.title),
		subjectId: (r.subject_id as string) ?? null,
		subjectName: (r.subject_name as string) ?? null,
		jurisdiction: (r.jurisdiction as string) ?? null,
		note: (r.note as string) ?? null,
		kind: String(r.kind ?? 'entity'),
		status: (r.status as CaseStatus) ?? 'open',
		priority: (r.priority as CasePriority) ?? 'normal',
		assignee: (r.assignee as string) ?? null,
		createdAt: new Date(r.created_at as string).toISOString(),
		threadCount: r.thread_count != null ? Number(r.thread_count) : undefined,
		activityCount: r.activity_count != null ? Number(r.activity_count) : undefined
	};
}

function rowToActivity(r: Record<string, unknown>): CaseActivity {
	return {
		id: String(r.id),
		kind: String(r.kind),
		detail: (r.detail as string) ?? null,
		actor: (r.actor as string) ?? null,
		createdAt: new Date(r.created_at as string).toISOString()
	};
}

function rowToThread(r: Record<string, unknown>): AskThread {
	return {
		id: String(r.id),
		question: String(r.question),
		answer: (r.answer as string) ?? null,
		intent: (r.intent as string) ?? null,
		model: (r.model as string) ?? null,
		createdAt: new Date(r.created_at as string).toISOString()
	};
}

/** Append an activity row to a case's timeline (best effort — never throws upward here). */
async function logActivity(
	client: pg.Pool | pg.PoolClient,
	caseId: string,
	kind: string,
	detail: string | null,
	actor = DEFAULT_ACTOR
): Promise<void> {
	await client.query(
		`insert into case_activity (case_id, kind, detail, actor) values ($1, $2, $3, $4)`,
		[caseId, kind, detail, actor]
	);
}

/** Insert a case and seed its timeline with a 'created' entry. */
export async function saveCase(c: NewCase): Promise<SavedCase | null> {
	const p = getPool();
	if (!p) return null;
	const { rows } = await p.query(
		`insert into saved_case (title, subject_id, subject_name, jurisdiction, note, kind, priority, assignee, payload)
		 values ($1, $2, $3, $4, $5, $6, $7, $8, $9)
		 returning id, title, subject_id, subject_name, jurisdiction, note, kind, status, priority, assignee, created_at`,
		[
			c.title,
			c.subjectId ?? null,
			c.subjectName ?? null,
			c.jurisdiction ?? null,
			c.note ?? null,
			c.kind ?? 'entity',
			c.priority ?? 'normal',
			c.assignee ?? null,
			c.payload != null ? JSON.stringify(c.payload) : null
		]
	);
	const saved = rowToCase(rows[0]);
	await logActivity(p, saved.id, 'created', `Case opened${c.kind ? ` · ${c.kind}` : ''}`);
	return saved;
}

/** List cases (optionally filtered by status), newest first, with thread + activity counts. */
export async function listCases(status?: CaseStatus, limit = 100): Promise<SavedCase[]> {
	const p = getPool();
	if (!p) return [];
	const filter = status ? `where c.status = $2` : '';
	const params: unknown[] = status ? [limit, status] : [limit];
	const { rows } = await p.query(
		`select c.id, c.title, c.subject_id, c.subject_name, c.jurisdiction, c.note, c.kind,
		        c.status, c.priority, c.assignee, c.created_at,
		        count(distinct t.id) as thread_count,
		        count(distinct a.id) as activity_count
		 from saved_case c
		 left join ask_thread t on t.case_id = c.id
		 left join case_activity a on a.case_id = c.id
		 ${filter}
		 group by c.id
		 order by c.created_at desc
		 limit $1`,
		params
	);
	return rows.map(rowToCase);
}

/** Counts by status for the queue summary bar. */
export async function caseSummary(): Promise<Record<CaseStatus, number>> {
	const base: Record<CaseStatus, number> = { open: 0, in_review: 0, escalated: 0, closed: 0 };
	const p = getPool();
	if (!p) return base;
	const { rows } = await p.query(`select status, count(*) n from saved_case group by status`);
	for (const r of rows) {
		const s = String(r.status) as CaseStatus;
		if (s in base) base[s] = Number(r.n);
	}
	return base;
}

/** Full case detail: the case, its activity timeline (newest first), and linked Ask threads. */
export async function getCaseDetail(id: string): Promise<CaseDetail | null> {
	const p = getPool();
	if (!p) return null;
	const caseRes = await p.query(
		`select id, title, subject_id, subject_name, jurisdiction, note, kind, status, priority, assignee, created_at
		 from saved_case where id = $1`,
		[id]
	);
	if (caseRes.rowCount === 0) return null;
	const [activities, threads] = await Promise.all([
		p.query(
			`select id, kind, detail, actor, created_at from case_activity where case_id = $1 order by created_at desc`,
			[id]
		),
		p.query(
			`select id, question, answer, intent, model, created_at from ask_thread where case_id = $1 order by created_at desc`,
			[id]
		)
	]);
	return {
		case: rowToCase(caseRes.rows[0]),
		activities: activities.rows.map(rowToActivity),
		threads: threads.rows.map(rowToThread)
	};
}

/** Patch a case's lifecycle fields, logging one activity per changed field. */
export async function updateCase(
	id: string,
	patch: { status?: CaseStatus; priority?: CasePriority; assignee?: string | null },
	actor = DEFAULT_ACTOR
): Promise<SavedCase | null> {
	const p = getPool();
	if (!p) return null;
	const current = await p.query(`select status, priority, assignee from saved_case where id = $1`, [id]);
	if (current.rowCount === 0) return null;
	const prev = current.rows[0] as { status: CaseStatus; priority: CasePriority; assignee: string | null };

	const sets: string[] = [];
	const params: unknown[] = [];
	const changes: Array<[string, string]> = [];
	if (patch.status && patch.status !== prev.status) {
		params.push(patch.status);
		sets.push(`status = $${params.length}`);
		changes.push(['status', `${prev.status} → ${patch.status}`]);
	}
	if (patch.priority && patch.priority !== prev.priority) {
		params.push(patch.priority);
		sets.push(`priority = $${params.length}`);
		changes.push(['priority', `${prev.priority} → ${patch.priority}`]);
	}
	if (patch.assignee !== undefined && patch.assignee !== prev.assignee) {
		params.push(patch.assignee);
		sets.push(`assignee = $${params.length}`);
		changes.push(['assigned', patch.assignee ? `assigned to ${patch.assignee}` : 'unassigned']);
	}
	if (sets.length === 0) {
		const unchanged = await p.query(
			`select id, title, subject_id, subject_name, jurisdiction, note, kind, status, priority, assignee, created_at
			 from saved_case where id = $1`,
			[id]
		);
		return rowToCase(unchanged.rows[0]);
	}
	params.push(id);
	const { rows } = await p.query(
		`update saved_case set ${sets.join(', ')} where id = $${params.length}
		 returning id, title, subject_id, subject_name, jurisdiction, note, kind, status, priority, assignee, created_at`,
		params
	);
	for (const [kind, detail] of changes) await logActivity(p, id, kind, detail, actor);
	return rowToCase(rows[0]);
}

/** Append a free-text note to a case's timeline. */
export async function addNote(id: string, body: string, actor = DEFAULT_ACTOR): Promise<CaseActivity | null> {
	const p = getPool();
	if (!p) return null;
	const exists = await p.query(`select 1 from saved_case where id = $1`, [id]);
	if (exists.rowCount === 0) return null;
	const { rows } = await p.query(
		`insert into case_activity (case_id, kind, detail, actor) values ($1, 'note', $2, $3)
		 returning id, kind, detail, actor, created_at`,
		[id, body, actor]
	);
	return rowToActivity(rows[0]);
}

/** Delete a case (activity + threads cascade / detach per FK rules). */
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
	return rowToThread(rows[0]);
}

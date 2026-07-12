import { config } from './config';

interface Shelf {
	rows?: unknown[];
	columns?: unknown[];
	values: unknown[];
	filters?: unknown[];
	sorts?: unknown[];
	limit?: number;
}

interface Opts {
	fetch?: typeof fetch;
	base?: string;
	user?: string;
	pass?: string;
}

interface OssieQueryResult {
	records: Record<string, unknown>[];
}

const CONNECTION = 'unknown_Benafide';
const MODEL = 'Benafide';

export async function ossieQuery(shelf: Shelf, o: Opts = {}): Promise<OssieQueryResult> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.saikuApi;
	const user = o.user ?? config.saikuUser;
	const pass = o.pass ?? config.saikuPass;
	const auth = 'Basic ' + Buffer.from(`${user}:${pass}`).toString('base64');
	const body = { connection: CONNECTION, model: MODEL, columns: [], filters: [], sorts: [], ...shelf };
	const r = await f(`${base}/rest/saiku/api/ai/ossie/query`, {
		method: 'POST',
		headers: { 'content-type': 'application/json', accept: 'application/json', authorization: auth },
		body: JSON.stringify(body)
	});
	if (!r.ok) return { records: [] };
	return (await r.json()) as OssieQueryResult;
}

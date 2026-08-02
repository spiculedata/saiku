/*
 * Saiku anonymous telemetry collector — Cloudflare Worker + D1.
 *
 * Counts *running instances*, not downloads (which lie badly for Docker). Each
 * Saiku instance POSTs a heartbeat on startup and once a day; we keep ONE row
 * per install and count distinct installs that have pinged more than once in the
 * last 30 days. The recurrence requirement filters out ephemeral one-shot pings
 * (throwaway containers, CI, one-off evals), each of which mints a fresh install
 * id and would otherwise inflate the count.
 *
 * Privacy, by construction:
 *   - the client IP is never read or stored;
 *   - the install id (already a random UUID) is stored only as sha256(id), so
 *     the stored value can't be correlated back to anything;
 *   - the payload is version + coarse platform only — no data, no hostnames.
 * The response carries the latest version, so the ping doubles as an update
 * check (a feature the user actually wants), not a naked beacon.
 */

export interface Env {
	DB: D1Database;
	LATEST_VERSION: string;
}

const WINDOW_SECONDS = 30 * 24 * 60 * 60; // active = seen in the last 30 days
const MAX_ID = 200;
const MAX_FIELD = 40;
// coarse platform fields must look boring; anything else is dropped, not stored
const SAFE = /^[A-Za-z0-9._+-]{1,40}$/;

// --- demo engagement (saiku#1636) ------------------------------------------
// The online demo posts anonymous interaction events here so we can see which
// surfaces visitors explore. Never fired by day-to-day self-hosted installs
// (client gates on demoMode + the telemetry opt-out). Coarse + capped.
const MAX_EVENTS = 50; // per request
const DEMO_WINDOW_SECONDS = 30 * 24 * 60 * 60;

// The demo is browser-driven, so /v1/event is a cross-origin POST from the demo
// origin. Allow it (the payload is anonymous + coarse); GET stats stays public.
const CORS: Record<string, string> = {
	'access-control-allow-origin': '*',
	'access-control-allow-methods': 'GET, POST, OPTIONS',
	'access-control-allow-headers': 'content-type',
	'access-control-max-age': '86400'
};

function str(v: unknown, max: number): string | null {
	if (typeof v !== 'string') return null;
	const t = v.trim();
	return t && t.length <= max ? t : null;
}

/** Optional coarse field: accept only the safe charset, else drop to null. */
function coarse(v: unknown): string | null {
	const t = str(v, MAX_FIELD);
	return t && SAFE.test(t) ? t : null;
}

async function sha256Hex(input: string): Promise<string> {
	const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input));
	return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
	return new Response(JSON.stringify(data), {
		status,
		headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...headers }
	});
}

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		const url = new URL(request.url);
		const now = Math.floor(Date.now() / 1000);

		// CORS preflight for the browser-posted demo events.
		if (request.method === 'OPTIONS') {
			return new Response(null, { status: 204, headers: CORS });
		}

		// --- demo engagement events (saiku#1636) --------------------------------
		// Anonymous, coarse interaction events from the online demo ONLY (the
		// client gates on demoMode + the telemetry opt-out). Best-effort: a bad or
		// oversized payload is dropped, never 5xx'd.
		if (url.pathname === '/v1/event' && request.method === 'POST') {
			let body: Record<string, unknown>;
			try {
				body = (await request.json()) as Record<string, unknown>;
			} catch {
				return json({ error: 'invalid json' }, 400, CORS);
			}
			const session = str(body.session, MAX_ID);
			if (!session) return json({ error: 'session required' }, 400, CORS);
			const version = coarse(body.version);
			const sessionHash = await sha256Hex(session);
			const rawEvents = Array.isArray(body.events) ? body.events : [];
			const rows: Array<{ type: string; name: string; detail: string | null }> = [];
			for (const e of rawEvents.slice(0, MAX_EVENTS)) {
				if (!e || typeof e !== 'object') continue;
				const ev = e as Record<string, unknown>;
				const type = coarse(ev.type);
				const name = coarse(ev.name);
				if (!type || !name) continue; // coarse-only; drop anything odd
				rows.push({ type, name, detail: coarse(ev.detail) });
			}
			if (rows.length === 0) return json({ ok: true, stored: 0 }, 200, CORS);
			try {
				const stmt = env.DB.prepare(
					`INSERT INTO demo_event (ts, session_hash, type, name, detail, version)
					 VALUES (?1, ?2, ?3, ?4, ?5, ?6)`
				);
				await env.DB.batch(
					rows.map((r) => stmt.bind(now, sessionHash, r.type, r.name, r.detail, version))
				);
			} catch {
				// best effort — never fail the caller
			}
			return json({ ok: true, stored: rows.length }, 200, CORS);
		}

		// --- demo engagement stats (public, cacheable) --------------------------
		if (url.pathname === '/v1/demo-stats' && request.method === 'GET') {
			const cutoff = now - DEMO_WINDOW_SECONDS;
			try {
				const totals = await env.DB.prepare(
					`SELECT count(*) AS events, count(DISTINCT session_hash) AS sessions
					 FROM demo_event WHERE ts > ?1`
				)
					.bind(cutoff)
					.first<{ events: number; sessions: number }>();
				const byAction = await env.DB.prepare(
					`SELECT type, name, count(*) AS n FROM demo_event
					 WHERE ts > ?1 GROUP BY type, name ORDER BY n DESC LIMIT 100`
				)
					.bind(cutoff)
					.all();
				return json(
					{
						events: totals?.events ?? 0,
						sessions: totals?.sessions ?? 0,
						windowDays: 30,
						byAction: byAction.results,
						generatedAt: now
					},
					200,
					{ 'cache-control': 'public, max-age=300', ...CORS }
				);
			} catch {
				return json({ error: 'stats unavailable' }, 500, CORS);
			}
		}

		// --- heartbeat + update check -------------------------------------------
		if (url.pathname === '/v1/check' && request.method === 'POST') {
			let body: Record<string, unknown>;
			try {
				body = (await request.json()) as Record<string, unknown>;
			} catch {
				return json({ error: 'invalid json' }, 400);
			}

			const id = str(body.id, MAX_ID);
			const version = coarse(body.version);
			if (!id || !version) return json({ error: 'id and version are required' }, 400);

			const idHash = await sha256Hex(id);
			const edition = coarse(body.edition);
			const os = coarse(body.os);
			const arch = coarse(body.arch);
			const java = coarse(body.java);

			// One row per install: insert on first sight, refresh last_seen after.
			// A storage hiccup must never break the client, so we swallow errors.
			try {
				await env.DB.prepare(
					`INSERT INTO installs (id_hash, version, edition, os, arch, java, first_seen, last_seen)
					 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7)
					 ON CONFLICT(id_hash) DO UPDATE SET
					   last_seen = ?7,
					   version   = ?2,
					   edition   = COALESCE(?3, edition),
					   os        = COALESCE(?4, os),
					   arch      = COALESCE(?5, arch),
					   java      = COALESCE(?6, java)`
				)
					.bind(idHash, version, edition, os, arch, java, now)
					.run();
			} catch {
				// best effort — never fail the caller
			}

			return json({ latest: env.LATEST_VERSION });
		}

		// --- public counts (cacheable) ------------------------------------------
		if (url.pathname === '/v1/stats' && request.method === 'GET') {
			const cutoff = now - WINDOW_SECONDS;
			// Count real releases only — exclude non-release builds ('dev', *SNAPSHOT*) that come
			// from CI, integration tests and IDE runs rather than deployed instances.
			const RELEASE_ONLY = "version <> 'dev' AND version NOT LIKE '%SNAPSHOT%'";
			// Count *recurring* installs only. A real deployment pings on startup AND once a day,
			// so a genuine install's last_seen advances past its first_seen. A never-repeated ping
			// (last_seen == first_seen) is almost always ephemeral — a throwaway `docker run`, a CI
			// container, a one-off eval — and since each fresh container mints a new install id,
			// counting one-shots inflates the number badly. Requiring recurrence keeps it honest.
			const RECURRING = 'last_seen > first_seen';
			const WHERE = `last_seen > ?1 AND ${RELEASE_ONLY} AND ${RECURRING}`;
			try {
				const active = await env.DB.prepare(`SELECT count(*) AS n FROM installs WHERE ${WHERE}`)
					.bind(cutoff)
					.first<{ n: number }>();
				const byVersion = await env.DB.prepare(
					`SELECT version, count(*) AS n FROM installs WHERE ${WHERE} GROUP BY version ORDER BY n DESC`
				)
					.bind(cutoff)
					.all();
				return json(
					{
						activeInstances: active?.n ?? 0,
						windowDays: 30,
						byVersion: byVersion.results,
						generatedAt: now
					},
					200,
					{ 'cache-control': 'public, max-age=3600', 'access-control-allow-origin': '*' }
				);
			} catch {
				return json({ error: 'stats unavailable' }, 500);
			}
		}

		if (url.pathname === '/') {
			return new Response('saiku telemetry collector — see /v1/stats\n', {
				status: 200,
				headers: { 'content-type': 'text/plain' }
			});
		}
		return json({ error: 'not found' }, 404);
	}
} satisfies ExportedHandler<Env>;

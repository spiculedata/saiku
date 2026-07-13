/*
 * Saiku anonymous telemetry collector — Cloudflare Worker + D1.
 *
 * Counts *running instances*, not downloads (which lie badly for Docker). Each
 * Saiku instance POSTs a heartbeat on startup and once a day; we keep ONE row
 * per install and count distinct installs seen in the last 30 days.
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
			try {
				const active = await env.DB.prepare(
					'SELECT count(*) AS n FROM installs WHERE last_seen > ?1'
				)
					.bind(cutoff)
					.first<{ n: number }>();
				const byVersion = await env.DB.prepare(
					'SELECT version, count(*) AS n FROM installs WHERE last_seen > ?1 GROUP BY version ORDER BY n DESC'
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

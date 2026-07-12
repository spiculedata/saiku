import type { Entity, SearchResult } from '$lib/types';
import { config } from './config';

interface Opts {
	fetch?: typeof fetch;
	base?: string;
}

interface RawEntity {
	id: string;
	name: string;
	jurisdiction?: string | null;
	status?: string | null;
}

interface RawSearchResponse {
	results?: RawEntity[];
	entities?: RawEntity[];
}

function toSearchResult(e: RawEntity): SearchResult {
	return { id: e.id, name: e.name, jurisdiction: e.jurisdiction ?? null, status: e.status ?? null };
}

const SEARCH_LIMIT = 12;

export async function searchEntities(q: string, o: Opts = {}): Promise<SearchResult[]> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.benafideApi;
	const url = `${base}/v1/entities?q=${encodeURIComponent(q)}&limit=${SEARCH_LIMIT}`;
	const r = await f(url, { headers: { accept: 'application/json' } });
	if (!r.ok) return [];
	const data: RawEntity[] | RawSearchResponse = await r.json();
	const rows = Array.isArray(data) ? data : data.results ?? data.entities ?? [];
	return rows.map(toSearchResult);
}

export async function getEntity(id: string, o: Opts = {}): Promise<Entity | null> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.benafideApi;
	const r = await f(`${base}/v1/entities/${encodeURIComponent(id)}`, { headers: { accept: 'application/json' } });
	if (!r.ok) return null;
	const e: RawEntity = await r.json();
	return { id: e.id, name: e.name, jurisdiction: e.jurisdiction ?? null, status: e.status ?? null };
}

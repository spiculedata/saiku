/*
 * Read-only bindings to the AI Query API's cube catalogue
 * (`GET /rest/saiku/api/ai/cubes`, `GET /rest/saiku/api/ai/schema/{cubeId}`) —
 * see `docs/AI-QUERY-API.md`. The Model IDE (saiku#1428) uses these to drive
 * schema-aware Monaco autocomplete: cube/measure/dimension/level names come
 * from the live catalogue, not from re-parsing the buffer being edited.
 */

const REST_BASE = '/rest/saiku/api/ai';

/** Mirrors `AiCubeSummary` (org.saiku.service.olap.ai). */
export interface AiCubeSummary {
	connectionName: string;
	catalog: string;
	schema: string;
	cubeName: string;
	cubeCaption?: string;
	defaultMeasure?: string;
	measureCount: number;
}

/** Trimmed projection of `AiSchema#toAgentView()` — only what completion needs. */
export interface AiCubeDetail {
	measures: Record<string, unknown>;
	dimensions: Record<
		string,
		{
			hierarchies?: Record<string, { levels?: Record<string, unknown> }>;
		}
	>;
}

export async function fetchCubeSummaries(): Promise<AiCubeSummary[]> {
	const res = await fetch(`${REST_BASE}/cubes`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) throw new Error(`ai/cubes -> ${res.status}`);
	return (await res.json()) as AiCubeSummary[];
}

/** `cubeId` is the raw `connection/catalog/schema/cube` string — NOT pre-encoded; each
 *  segment is encoded here so names containing `/` or spaces round-trip correctly. */
export async function fetchCubeDetail(cubeId: string): Promise<AiCubeDetail> {
	const encoded = cubeId
		.split('/')
		.map((seg) => encodeURIComponent(seg))
		.join('/');
	const res = await fetch(`${REST_BASE}/schema/${encoded}`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) throw new Error(`ai/schema/${cubeId} -> ${res.status}`);
	return (await res.json()) as AiCubeDetail;
}

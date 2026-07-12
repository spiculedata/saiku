export interface Entity {
	id: string;
	name: string;
	jurisdiction: string | null;
	status: string | null;
}

export interface EntityRisk {
	entity_id: string;
	risk_score: number | null;
	opacity_score?: number | null;
}

export interface SearchResult {
	id: string;
	name: string;
	jurisdiction: string | null;
	status: string | null;
}

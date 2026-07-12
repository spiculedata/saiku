export interface Entity {
	id: string;
	name: string;
	jurisdiction: string | null;
	status: string | null;
}

export interface SearchResult {
	id: string;
	name: string;
	jurisdiction: string | null;
	status: string | null;
}

/** Entity profile as served by the Ossie /entity endpoint — attributes + risk + opacity. */
export interface EntityProfile {
	id: string;
	name: string;
	jurisdiction: string | null;
	status: string | null;
	risk_score?: number | null;
	opacity_score?: number | null;
}

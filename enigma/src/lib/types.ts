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

/**
 * Client-safe mirror of the server-only `GraphNode`/`GraphEdge`/`OwnershipGraph`
 * shapes in `$lib/server/saiku`. Components must import these instead of the
 * server module, which pulls in server-only env/config.
 */
export interface GraphNode {
	id: string;
	label: string;
	kind: string;
}

export interface GraphEdge {
	owned: string;
	owner: string;
	percentage: number | null;
	depth: number;
	cycle: boolean;
}

export interface OwnershipGraph {
	rootId: string;
	nodes: GraphNode[];
	edges: GraphEdge[];
	maxDepth: number;
	hasCycle: boolean;
}

/** A single label/value pair feeding a Deck chart (bar, hbar, or donut). */
export interface ChartRow {
	label: string;
	value: number;
}

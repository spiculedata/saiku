/**
 * Join-group derivation — pure helpers shared by SchemaCanvasView (which
 * needs the physical/semantic counts for the toolbar + canvas chip) and
 * JoinsPanel (which renders the groups). Lifted out of SchemaCanvasView.svelte
 * (audit finding #1040, template-decompose pass).
 */
import { SchemaCanvasStore } from './state.svelte.js';
import type { SchemaCanvasJoin } from './types.js';

/** One rendered join group: a display label + the canonical key (so rename
 *  writes land in the right slot) + every join under that label. */
export interface JoinGroupRender {
	label: string;
	canonicalKey: string;
	joins: SchemaCanvasJoin[];
}

/**
 * Group every join by its rendered label (renames applied). Stable insertion
 * order = the order each group's first join appears in `store.doc.joins`.
 */
export function computeJoinGroups(store: SchemaCanvasStore): JoinGroupRender[] {
	const out: JoinGroupRender[] = [];
	const byLabel = new Map<string, JoinGroupRender>();
	for (const j of store.doc.joins) {
		const canonicalKey = SchemaCanvasStore.joinCanonicalKey(j);
		const label = store.joinGroupLabelFor(j);
		let g = byLabel.get(label);
		if (!g) {
			g = { label, canonicalKey, joins: [] };
			byLabel.set(label, g);
			out.push(g);
		}
		g.joins.push(j);
	}
	return out;
}

/**
 * A group is "semantic" iff EVERY join in it is a cube-link / inferred-fk.
 * Mixed groups (same column pair in both physical AND a cube-link) stay in
 * the physical bucket since the physical statement wins.
 */
export function isSemanticGroup(g: JoinGroupRender): boolean {
	return g.joins.every((j) => j.origin === 'cube-link' || j.origin === 'inferred-fk');
}

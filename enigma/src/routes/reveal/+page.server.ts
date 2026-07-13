import type { PageServerLoad } from './$types';
import { ossieEntity, ossieGraph } from '$lib/server/saiku';

/**
 * The featured "case file". A curated subject with a genuinely interesting
 * structure — a Bahamas company at the top of Benafide's computed risk score,
 * held up through a chain of Luxembourg intermediaries. Rotate by changing this id
 * (or pass ?id= to preview another). Everything on the page is derived live from
 * the entity profile + ownership graph — no hard-coded facts.
 */
const CASE_OF_THE_WEEK = 'GLEIF-549300JQHRUX6QUG4K03';
const DEPTH = 4;

export const load: PageServerLoad = async ({ fetch, url }) => {
	const id = url.searchParams.get('id') ?? CASE_OF_THE_WEEK;
	const [subject, graph] = await Promise.all([
		ossieEntity(id, { fetch }),
		ossieGraph(id, DEPTH, { fetch })
	]);

	// Derive the story from the graph: the corporate layers (entities other than the
	// subject) and the ultimate human owners (persons), de-duplicated by label.
	const seenLayer = new Set<string>();
	const layers: string[] = [];
	const seenPerson = new Set<string>();
	const people: string[] = [];
	if (graph) {
		for (const n of graph.nodes) {
			if (n.id === graph.rootId) continue;
			if (n.kind === 'person') {
				if (!seenPerson.has(n.label)) {
					seenPerson.add(n.label);
					people.push(n.label);
				}
			} else if (!seenLayer.has(n.label)) {
				seenLayer.add(n.label);
				layers.push(n.label);
			}
		}
	}

	return {
		subject,
		id,
		layers,
		people,
		stats: {
			owners: people.length,
			layers: layers.length,
			depth: graph?.maxDepth ?? 0,
			hasCycle: graph?.hasCycle ?? false,
			hasGraph: Boolean(graph && graph.edges.length > 0)
		}
	};
};

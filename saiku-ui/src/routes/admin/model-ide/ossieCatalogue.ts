import type { OssieCatalogue } from '$lib/monaco/ossie-yaml-completions';
import type { IdeSelection } from './bufferKey';

/** Returned whenever the shared catalogue does not belong to the open Ossie file. */
export const EMPTY_OSSIE_CATALOGUE: OssieCatalogue = Object.freeze({
	datasets: [],
	datasetFields: {},
	metrics: []
}) as OssieCatalogue;

/**
 * The catalogue the Ossie YAML completion provider may use right now: the shared live
 * catalogue only when it mirrors the open Ossie datasource, otherwise an empty one. Without
 * this guard, switching A → B → A (or a late prefetch for A landing while B is open) offered
 * the other datasource's dataset and field names (PR #2490 review).
 */
export function catalogueFor(
	selected: IdeSelection | null,
	catalogueDs: string | null,
	catalogue: OssieCatalogue
): OssieCatalogue {
	return selected?.kind === 'ossie' &&
		catalogueDs !== null &&
		selected.datasource.name === catalogueDs
		? catalogue
		: EMPTY_OSSIE_CATALOGUE;
}

/** True when a prefetch for `datasourceName` may still be applied: that datasource is the open file. */
export function isStillSelected(selected: IdeSelection | null, datasourceName: string): boolean {
	return selected?.kind === 'ossie' && selected.datasource.name === datasourceName;
}

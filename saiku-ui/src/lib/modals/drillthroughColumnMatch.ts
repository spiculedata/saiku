/**
 * saiku#822/#823 — matches a cube dimension/measure's `uniqueName` against
 * the MDX-qualified column labels a drillthrough column-discovery endpoint
 * (`GET .../drillthrough/columns`) returns, so DrillthroughModal can narrow
 * its picker to exactly the columns a given query can drill through.
 *
 * Discovery answers with LEVEL-qualified labels (e.g. `[Time].[Time].[Year]`
 * for a dimension, `[Measures].[Store Sales]` for a measure), while the
 * modal's checkboxes are keyed one-per-Dimension/Measure at the coarser
 * `uniqueName` granularity (`[Time]`, `[Measures].[Store Sales]`). A measure
 * uniqueName is already fully qualified, so it matches by exact string
 * equality; a dimension uniqueName only ever matches as a bracket-prefix of
 * one of its levels' discovered names.
 */
export function isDrillthroughColumnDiscovered(
	uniqueName: string,
	discovered: ReadonlySet<string>
): boolean {
	if (discovered.has(uniqueName)) return true;
	const prefix = `${uniqueName}.`;
	for (const name of discovered) {
		if (name.startsWith(prefix)) return true;
	}
	return false;
}

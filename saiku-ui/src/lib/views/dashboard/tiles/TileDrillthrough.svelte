<script lang="ts">
	/*
	 * Issue #930 — drillthrough on cell click (shared tile orchestration).
	 *
	 * ChartTile and TableTile both need the identical flow when a data point /
	 * measure cell is right-clicked: open the column-picker (reusing the
	 * workspace DrillthroughModal), load the cube's dimensions/measures for it,
	 * then on Run call aiDrillthrough(queryId, {position, maxRows, returns}) and
	 * render the rows in DrillthroughResultModal. This component owns both
	 * modals + the metadata fetch so the tiles only have to compute a position
	 * and call `open(queryId, position)`.
	 *
	 * Active dashboard filters are honoured automatically: the query identified
	 * by `queryId` already had them merged when it was executed by the tile.
	 */

	import DrillthroughModal from '$lib/modals/DrillthroughModal.svelte';
	import DrillthroughResultModal from '$lib/modals/DrillthroughResultModal.svelte';
	import { aiDrillthrough, downloadAiDrillthroughCsv } from '$lib/api/aiQuery';
	import { drillthroughToQueryResult } from '$lib/dashboard/drillthroughCoord';
	import { datasources } from '$lib/stores/datasources.svelte';
	import { session } from '$lib/stores/session.svelte';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import type { CubeRef } from '$lib/api/dashboards';
	import type { SaikuCube, SaikuDimension, SaikuMeasure } from '$lib/api/discover';
	import type { QueryResult } from '$lib/api/query';

	interface Props {
		/** The cube the tile renders — used to load the picker's dim/measure list. */
		cube: CubeRef | null;
	}

	let { cube }: Props = $props();

	let pickerOpen = $state(false);
	let resultOpen = $state(false);
	let result = $state<QueryResult | null>(null);
	let dimensions = $state<SaikuDimension[]>([]);
	let measures = $state<SaikuMeasure[]>([]);

	// queryId + cell position captured at right-click time, consumed on Run.
	let activeQueryId = $state<string | null>(null);
	let activePosition = $state<string | null>(null);

	/** Build a SaikuCube (what datasources.metadata wants) from the tile's
	 *  CubeRef. uniqueName/caption/visible aren't needed for the metadata
	 *  lookup (which keys off connection/catalog/schema/name) so they're
	 *  filled with sensible placeholders. */
	function toSaikuCube(ref: CubeRef): SaikuCube {
		return {
			connection: ref.connectionName,
			catalog: ref.catalog,
			schema: ref.schema,
			name: ref.cubeName,
			caption: ref.cubeName,
			uniqueName: ref.cubeName,
			visible: true
		};
	}

	/** Entry point for the tiles. Opens the column picker for the given
	 *  already-executed query + cell position. No-op without a cube/session. */
	export async function open(queryId: string, position: string | null): Promise<void> {
		if (!cube || !session.current) return;
		activeQueryId = queryId;
		activePosition = position;
		dimensions = [];
		measures = [];
		pickerOpen = true;
		try {
			const md = await datasources.metadata(session.current.username, toSaikuCube(cube));
			dimensions = md.dimensions;
			measures = md.measures;
		} catch (err) {
			// Picker still opens (with empty lists) so the user can Run an
			// all-columns drillthrough; surface the metadata failure as a toast.
			toasts.danger(i18n.t('toast.drillFailed'), err instanceof Error ? err.message : String(err));
		}
	}

	async function runDrillthrough(opts: {
		dimensions: string[];
		measures: string[];
		maxRows: number;
	}): Promise<void> {
		pickerOpen = false;
		if (!activeQueryId) return;
		const returns = [...opts.dimensions, ...opts.measures];
		result = null;
		resultOpen = true;
		try {
			const dt = await aiDrillthrough(activeQueryId, {
				position: activePosition ?? undefined,
				maxRows: opts.maxRows,
				returns: returns.length ? returns : undefined
			});
			result = drillthroughToQueryResult(dt);
		} catch (err) {
			resultOpen = false;
			toasts.danger(i18n.t('toast.drillFailed'), err instanceof Error ? err.message : String(err));
		}
	}

	function exportCsv(opts: { dimensions: string[]; measures: string[] }): void {
		// Issue #1051: stream the drillthrough as a CSV file from the AI Query
		// surface (GET /ai/query/{queryId}/drillthrough/export/csv). The endpoint
		// sets Content-Disposition: attachment, so opening the same-origin
		// authenticated URL downloads the file directly — same mechanism the
		// Workspace export uses. Honours the picked returns + the captured
		// cell position, replacing the earlier JSON-refetch fallback.
		pickerOpen = false;
		if (!activeQueryId) return;
		const returns = [...opts.dimensions, ...opts.measures];
		downloadAiDrillthroughCsv(activeQueryId, {
			position: activePosition ?? undefined,
			maxRows: 10000,
			returns: returns.length ? returns : undefined
		});
	}
</script>

<DrillthroughModal
	{dimensions}
	{measures}
	maxRows={1000}
	open={pickerOpen}
	onRun={runDrillthrough}
	onExportCsv={exportCsv}
	onCancel={() => (pickerOpen = false)}
/>

<DrillthroughResultModal {result} open={resultOpen} onClose={() => (resultOpen = false)} />

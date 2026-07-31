<!--
  Tiny child of SvelteFlow that watches store.requestedJumpTarget and
  store.requestedCanvasAction, dispatching each to the matching
  useSvelteFlow() imperative method (setCenter / zoomIn / zoomOut /
  fitView / zoomTo). The hook only works inside the SvelteFlow
  subtree, so this component sits next to <Background>/<Controls>/<MiniMap>
  and provides the AI (and any other consumer) with a store-mediated
  view-controls API. Renders no DOM of its own.
-->
<script lang="ts">
	import { useSvelteFlow } from '@xyflow/svelte';
	import { onMount, tick } from 'svelte';
	import { SchemaCanvasStore } from './state.svelte.js';

	interface Props {
		store: SchemaCanvasStore;
	}

	let { store }: Props = $props();
	const flow = useSvelteFlow();

	// Approx node dims used to centre on the card's middle, not its
	// top-left corner. The table cards have a fixed width in TableNode
	// and a variable height depending on visible columns — using the
	// width is safe; the height estimate keeps the card roughly centred
	// without needing a real measurement pass.
	const APPROX_NODE_W = 240;
	const APPROX_NODE_H = 220;

	// One-shot fit-on-mount (#1085 + #1086).  The <SvelteFlow fitView>
	// prop re-triggers when `bind:nodes` mutates (delete a table →
	// viewport jerks); passing it imperatively here fires exactly
	// once, at mount, and never again.  `maxZoom` caps the initial
	// zoom so a canvas with one small table doesn't fill the viewport
	// with that single card.
	onMount(() => {
		void (async () => {
			await tick();
			await flow.fitView({ padding: 0.2, maxZoom: 0.9 });
		})();
	});

	$effect(() => {
		const target = store.requestedJumpTarget;
		if (!target) return;
		const t = store.doc.tables.find((tt) => tt.id === target.tableId);
		if (!t) return;
		(async () => {
			// Wait a tick so any preceding store-driven node updates have
			// flushed into SvelteFlow's internal position cache before we
			// ask it to centre.
			await tick();
			await flow.setCenter(t.position.x + APPROX_NODE_W / 2, t.position.y + APPROX_NODE_H / 2, {
				duration: 350,
				zoom: 1
			});
		})();
	});

	// Viewport action dispatch — DimSum (or any UI) sets
	// store.requestedCanvasAction, we execute against the imperative
	// SvelteFlow API and clear.  center_view fits to all nodes, which is
	// what "center everything on canvas" naturally means.
	$effect(() => {
		const req = store.requestedCanvasAction;
		if (!req) return;
		(async () => {
			await tick();
			switch (req.kind) {
				case 'zoom_in':
					await flow.zoomIn({ duration: 250 });
					break;
				case 'zoom_out':
					await flow.zoomOut({ duration: 250 });
					break;
				case 'zoom_to_100': {
					// Keep the current pan, only change zoom to 100 %.
					// setViewport is on the public type; zoomTo isn't.
					const vp = flow.getViewport();
					await flow.setViewport({ x: vp.x, y: vp.y, zoom: 1 }, { duration: 250 });
					break;
				}
				case 'fit_view':
				case 'center_view':
					await flow.fitView({ duration: 350, padding: 0.15 });
					break;
			}
		})();
	});
</script>

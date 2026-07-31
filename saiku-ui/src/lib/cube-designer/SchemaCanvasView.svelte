<!--
  Canvas schema designer — main canvas view.

  Hosts @xyflow/svelte's SvelteFlow with a custom TableNode type, the
  left TableSidebar, a top toolbar (mode switch, Arrange button), and the
  pane drop-target that creates new TableNode instances when the user
  drags from the sidebar.

  Column-to-column edges are created via SvelteFlow's onconnect — when
  the user drags from an output handle on table A's column to an input
  handle on table B's column, we mint a SchemaCanvasJoin and the
  auto-suggester offers more candidate joins between the same pair if
  any are obvious.
-->
<script lang="ts">
	import type { Node, Edge } from '@xyflow/svelte';
	import { untrack } from 'svelte';
	import { ChevronRight, Sparkles } from 'lucide-svelte';
	import AskDimSumWidget from './AskDimSumWidget.svelte';
	import CanvasToolbar from './CanvasToolbar.svelte';
	import DimSumChatDock from './DimSumChatDock.svelte';
	import JoinsPanel from './JoinsPanel.svelte';
	import CanvasPane from './CanvasPane.svelte';
	import Toast from '$lib/design-system/Toast.svelte';
	import { computeJoinGroups, isSemanticGroup } from './join-groups.js';
	import type {
		AnthropicToolUseBlock,
		AnthropicToolResultBlock,
		ChatMessage,
		DimSumMutationResult
	} from './ai-chat-types';
	import type { SchemaCanvasStore } from './state.svelte.js';
	import { applyLayout, type LayoutMode } from './layout.js';
	import { buildCanvasSummary, executeDimSumTool, DIMSUM_MUTATION_TOOLS } from './dimsum-tools.js';
	import { postDimSumTurn } from './dimsum-agent.js';
	import { getCubeDesignerAI } from './backend';
	import { buildFlowNodes, buildFlowEdges, type FlowFocus } from './canvas-flow.js';

	interface Props {
		store: SchemaCanvasStore;
		/** Optional snippet threaded to TableSidebar as its sourceContext —
		 *  the canvas page uses this to put the Source + Schema pickers
		 *  above the source-tables list instead of the top toolbar. */
		sourceContext?: import('svelte').Snippet;
		/** Draft-with-AI seed: a description auto-fired into DimSum once the
		 *  connection's table catalog has loaded (from /schemas → canvas). */
		seedIntent?: string;
		/** True when the page arrived via `?openFrom` (opening a saved schema).
		 *  The chat starts EMPTY instead of restoring the connection's chat, so
		 *  the opened schema's conversation is fresh with no flash of the old one. */
		openArrival?: boolean;
	}

	let { store, sourceContext, seedIntent, openArrival }: Props = $props();

	// Injected AI adapter (optional). Its fetchImpl points the DimSum turn at the
	// host's AI endpoint; absent ⇒ default transport (/api/inference/dimsum).
	const designerAI = getCubeDesignerAI();

	// Single-join selection (clicking a row in the joins panel) layers
	// on top of group/table focus: when a join id is selected, ONLY that
	// join + its two endpoints stay vivid; everything else fades. Lets a
	// user inspect one specific relationship without losing the wider
	// focus model.
	const focusedTableIds = $derived.by(() => {
		const out = new Set<string>(store.tableIdsInFocus());
		const selJoinId = store.selectedJoinId;
		if (selJoinId !== null) {
			const j = store.doc.joins.find((jj) => jj.id === selJoinId);
			if (j) {
				out.clear();
				out.add(j.sourceTableId);
				out.add(j.targetTableId);
			}
		}
		return out;
	});
	const focusedJoinIds = $derived.by(() => {
		const out = new Set<string>(store.joinIdsInFocus());
		const selJoinId = store.selectedJoinId;
		if (selJoinId !== null) {
			out.clear();
			out.add(selJoinId);
		}
		return out;
	});
	// Focus only "activates" (fade everything else) when the selection
	// actually pulls in more than itself. A table with NO joins to any
	// other table would otherwise drop the whole canvas to 20% opacity
	// and leave the lonely card sitting alone — not useful. In that
	// case treat focus as inactive so the rest of the canvas stays
	// readable.
	// Pick mode is the highest-priority signal — it forces focusActive
	// OFF so no table can fade behind a focus that was lingering from
	// before pick mode started (or that got set by another path mid-
	// pick).  Without this gate the user clicks a column, the parent
	// table picks up SvelteFlow's selection, and the rest of the canvas
	// dims out from under their next pick.
	// AI-assist section state — brought back into the sidebar.  Lives
	// below the source-tables list (anchored to the bottom of the
	// column). Owns its own intent textarea, loading + error state, and
	// result summary so the host page doesn't have to thread anything
	// through.  Restore of the pre-drop shape from commit 37e1c6b.
	// Minimised by default — a fresh/opened canvas shows only the collapsed
	// "Ask DimSum" header (+ turn count). Any AI activity (Draft-with-AI seed,
	// manual draft, or a chat send — all funnel through runDraftWithAI) expands
	// it so the conversation is visible. Reported 2026-07-29.
	let dimSumExpanded = $state(false);
	let aiIntent = $state('');
	let aiChatOpen = $state(false);
	let aiChatDraft = $state('');
	let aiDrafting = $state(false);
	let aiError = $state<string | null>(null);
	/** Modal open state for the "See issue" full-text error view. */
	let aiErrorOpen = $state(false);
	// Rolling multi-turn conversation.  Populated on every Ask AI + every
	// send from the chat modal; both the sidebar widget AND the chat
	// modal read from this list.  Persisted per connection so a page
	// reload doesn't lose context.  Shared types live in ./ai-chat-types
	// so AskDimSumWidget can render blocks without duplicating the shape.
	let aiMessages = $state<ChatMessage[]>([]);
	function chatStorageKey(): string {
		return `saiku.canvas.ai.chat:${store.connectionId || 'unbound'}`;
	}
	// Restore saved chat on mount + on connection switch — but NOT on
	// every re-render.  Bug I hit before: the effect re-fired mid-loop
	// and wiped fresh in-flight messages before the assistant's reply
	// landed.  Track the last-restored connection so we only reload
	// when it actually changes.
	let lastRestoredConnection: string | null = null;
	$effect(() => {
		if (typeof window === 'undefined') return;
		const currentConn = store.connectionId || 'unbound';
		if (lastRestoredConnection === currentConn) return;
		lastRestoredConnection = currentConn;
		// Opening a saved schema (?openFrom): don't restore the connection's chat
		// — it belonged to a different schema/draft. Start empty + persist so the
		// opened schema's conversation is fresh, with no flash of the old chat.
		if (openArrival) {
			aiMessages = [];
			persistChat();
			return;
		}
		try {
			const raw = window.localStorage.getItem(chatStorageKey());
			aiMessages = raw ? (JSON.parse(raw) as ChatMessage[]) : [];
		} catch {
			aiMessages = [];
		}
	});
	function persistChat() {
		if (typeof window === 'undefined') return;
		try {
			window.localStorage.setItem(chatStorageKey(), JSON.stringify(aiMessages));
		} catch {
			/* quota / private browsing — non-fatal */
		}
	}
	function clearChat() {
		aiMessages = [];
		persistChat();
	}
	// Reset the DimSum chat when the user switches to a different cube — each
	// cube gets a fresh conversation instead of one ever-growing thread. The
	// first non-null cube is adopted silently so the restored chat isn't wiped
	// on load, and a remount (which preserves store.activeCubeId) doesn't count
	// as a switch.
	let lastChatCubeId: string | null = null;
	$effect(() => {
		const cid = store.activeCubeId;
		if (cid === null) return;
		if (lastChatCubeId === null) {
			lastChatCubeId = cid;
			return;
		}
		if (cid !== lastChatCubeId) {
			lastChatCubeId = cid;
			clearChat();
		}
	});
	// Draft-with-AI seed (from /schemas): auto-fire the description into
	// DimSum exactly once, after the connection's table catalog has loaded
	// (so list_tables / describe_table see real tables), with a higher
	// iteration cap since a full star schema needs many tool turns. The draft
	// surfaces in the always-visible floating sidebar widget — we deliberately
	// DON'T pop open the larger chat dock (aiChatOpen), which would duplicate
	// the same conversation the sidebar is already showing (reported
	// 2026-07-29). The user pops the dock out themselves via the maximize icon.
	let seedFired = false;
	$effect(() => {
		if (seedFired || !seedIntent?.trim()) return;
		if (!store.connectionId || store.sourceLoading || store.sourceTables.length === 0) return;
		seedFired = true;
		// A Draft-with-AI request starts a CLEAN conversation — wipe any chat
		// restored from a prior session (or retained in memory across an SPA
		// nav) so the drafted cube isn't appended to an unrelated thread.
		clearChat();
		void runDraftWithAI(seedIntent, 16);
	});
	// Opening/duplicating a saved schema from the library (loadFromLibrary bumps
	// store.chatResetNonce) starts a fresh DimSum conversation — the connection's
	// restored chat belonged to a different schema/draft.
	let lastChatResetNonce = store.chatResetNonce;
	$effect(() => {
		const n = store.chatResetNonce;
		if (n !== lastChatResetNonce) {
			lastChatResetNonce = n;
			clearChat();
		}
	});
	// The popped-out chat dock owns its own log-scroll ref + effect (see
	// DimSumChatDock). Here we keep only the inline sidebar-chat scroll.
	// Same pattern for the inline sidebar chat — pins the message log
	// to the bottom whenever a turn lands.  Fires only while the section
	// is open (bind:this stays null otherwise).
	let aiSidebarLogEl = $state<HTMLDivElement | null>(null);
	$effect(() => {
		if (!dimSumExpanded) return;
		void aiMessages.length;
		void aiDrafting;
		queueMicrotask(() => {
			if (aiSidebarLogEl) aiSidebarLogEl.scrollTop = aiSidebarLogEl.scrollHeight;
		});
	});
	let aiResult = $state<DimSumMutationResult | null>(null);

	async function runDraftWithAI(overrideIntent?: string, maxIterations = 8) {
		const intent = (overrideIntent ?? aiIntent).trim();
		if (aiDrafting || !store.connectionId || !intent) return;
		aiDrafting = true;
		aiError = null;
		aiResult = null;
		// Doing AI stuff → expand the (minimised-by-default) sidebar so the
		// conversation is visible as DimSum works. We stop here: the larger
		// popped-out chat DOCK (aiChatOpen) stays closed — the sidebar already
		// shows this conversation, so opening both is a duplicate. The user
		// pops the dock out themselves via the maximize icon.
		dimSumExpanded = true;

		// Snapshot the pre-turn state so Cancel + toolbar Undo can revert
		// the whole batch of mutations DimSum makes in this turn.  JSON
		// round-trip because structuredClone can't clone Svelte 5 `$state`
		// proxies (DataCloneError).
		const preTurnDoc = JSON.parse(JSON.stringify(store.doc)) as typeof store.doc;
		const preTableCount = store.doc.tables.length;
		const preJoinCount = store.doc.joins.length;

		// Append user's turn to the running history.
		aiMessages = [...aiMessages, { role: 'user', content: intent, ts: Date.now() }];
		persistChat();

		try {
			// Agent loop — up to `maxIterations` (default 8) to keep worst-case
			// cost bounded.  A from-scratch "draft me a cube" seed passes a
			// higher cap since a full star schema needs many tool turns (tables
			// + joins + dimensions + levels + measures).  Each iteration: send
			// messages → get content blocks → if any tool_use, execute + append
			// tool_result + loop; if only text, we're done.
			let mutated = false;
			for (let iter = 0; iter < maxIterations; iter++) {
				const canvasSummary = buildCanvasSummary(store);
				// saiku-cloud#1078: the DimSum agent loop has its own gateway
				// endpoint now (server owns the system prompt + tool schemas);
				// /api/inference/propose stays the one-shot SchemaProfile flow.
				// Request/response shaping + gateway error handling live in
				// ./dimsum-agent so the wire format is unit-testable.
				const content = await postDimSumTurn(aiMessages, canvasSummary, designerAI?.fetchImpl);
				// Append Claude's response to history verbatim.
				aiMessages = [...aiMessages, { role: 'assistant', content, ts: Date.now() }];
				persistChat();

				const toolUses = content.filter((b): b is AnthropicToolUseBlock => b.type === 'tool_use');
				if (toolUses.length === 0) break; // Claude is done — text-only response.

				const toolResults: AnthropicToolResultBlock[] = [];
				for (const tu of toolUses) {
					const { content: result, isError } = await executeDimSumTool(store, tu.name, tu.input, {
						arrangeCanvas: handleLayout
					});
					toolResults.push({
						type: 'tool_result',
						tool_use_id: tu.id,
						content: result,
						is_error: isError
					});
					// Track whether ANY mutation succeeded so we can show the
					// Confirm/Cancel banner after the loop settles.
					if (!isError && DIMSUM_MUTATION_TOOLS.has(tu.name)) mutated = true;
				}
				aiMessages = [...aiMessages, { role: 'user', content: toolResults, ts: Date.now() }];
				persistChat();
			}

			// Wire up the Confirm/Cancel banner if the loop actually
			// touched the canvas.  Cancel reverts to the pre-turn snapshot
			// via the store's undo slot.
			if (mutated) {
				store.previousDoc = preTurnDoc;
				aiResult = {
					tables: store.doc.tables.length - preTableCount,
					joins: store.doc.joins.length - preJoinCount,
					warnings: []
				};
			}
		} catch (e) {
			aiError = e instanceof Error ? e.message : 'AI call failed.';
		} finally {
			aiDrafting = false;
			if (overrideIntent === undefined) aiIntent = '';
			else aiChatDraft = '';
		}
	}

	const focusActive = $derived(
		!store.pickModeActive &&
			((store.focusKind !== 'none' && focusedTableIds.size > 1) || store.selectedJoinId !== null)
	);
	// Per-(tableId, columnName) keys of every column endpoint participating
	// in a focused join. TableNode highlights matching column rows green so
	// the user can see WHICH columns the focused group/table is wired
	// through, not just which tables are connected.

	const focusedColumnKeys = $derived.by(() => {
		const out = new Set<string>();
		if (!focusActive) return out;
		// Gated by the toggle so users who'd rather follow the lines
		// (and keep the layout stable) don't get the in-table green
		// highlight + auto-expand.  Group focus auto-flips the toggle
		// ON via store.focusGroup, but the user is still free to turn
		// it off mid-focus if they want.
		if (!store.highlightFocusedColumns) return out;
		for (const j of store.doc.joins) {
			if (!focusedJoinIds.has(j.id)) continue;
			out.add(`${j.sourceTableId}:${j.sourceColumnName}`);
			out.add(`${j.targetTableId}:${j.targetColumnName}`);
		}
		return out;
	});
	// `${tableId}:${columnName}` keys for EVERY column that participates
	// in ANY join — so TableNode can hide the handle dots on columns
	// that aren't wired (they're still in the DOM as functional anchor
	// points but rendered invisible unless the column row is hovered).
	const connectedColumnKeys = $derived.by(() => {
		const out = new Set<string>();
		for (const j of store.doc.joins) {
			out.add(`${j.sourceTableId}:${j.sourceColumnName}`);
			out.add(`${j.targetTableId}:${j.targetColumnName}`);
		}
		return out;
	});

	// Node/edge derivations are pure — see ./canvas-flow (unit-tested). The
	// component owns only the reactive plumbing (focus deriveds in, $state
	// bindings + sync effects below).
	const flowFocus = $derived<FlowFocus>({
		focusActive,
		focusedTableIds,
		focusedColumnKeys,
		connectedColumnKeys,
		focusedJoinIds
	});
	const flowNodes = $derived<Node[]>(buildFlowNodes(store, flowFocus));
	const flowEdges = $derived<Edge[]>(buildFlowEdges(store, flowFocus));

	// Wrap in $state so SvelteFlow's bindings can write back. SvelteFlow
	// mutates the bound arrays in place when the user drags nodes,
	// selects them, etc. — we then sync those mutations back to the
	// authoritative store via $effects below.
	let nodes = $state<Node[]>([]);
	let edges = $state<Edge[]>([]);
	$effect(() => {
		nodes = flowNodes;
	});
	$effect(() => {
		edges = flowEdges;
	});

	// Sync user-driven position changes from SvelteFlow's nodes array
	// back to the store. We compare against the store's stored positions
	// so we don't loop on store-driven updates.
	$effect(() => {
		for (const n of nodes) {
			const stored = store.doc.tables.find((t) => t.id === n.id);
			if (!stored) continue;
			if (stored.position.x !== n.position.x || stored.position.y !== n.position.y) {
				store.moveTable(n.id, { x: n.position.x, y: n.position.y });
			}
			if ((n.selected ?? false) !== (store.selectedTableId === n.id)) {
				store.selectedTableId = n.selected ? n.id : null;
			}
		}
	});

	// Edges-sync — propagate xyflow-driven selection changes back to the
	// store.  Tricky because the bound `edges` array uses SvelteFlow-style
	// ids (`edge:tbl_xxx::tbl_yyy`, one per table-pair) while the store's
	// `selectedJoinId` is a real join id (`join_xxx`).  Two distinct
	// formats — mapping naïvely (writing `e.id` into selectedJoinId)
	// silently corrupts selection because focusedColumnKeys can then
	// never match any `j.id`, so the endpoint rows never light up green
	// AND the canvas gets stuck ping-ponging between flowEdges and
	// edges-sync rewriting selectedJoinId every microtask.  The previous
	// version of this effect did exactly that, which is what made the
	// "click join → click pane" highlight refuse to clear: it was a
	// reactivity-cycle artefact, not a "the clear didn't run" bug.
	//
	// Two cases to handle:
	//
	//  1. xyflow reports an edge `selected: true` (user clicked the
	//     curve).  For a single-join edge we can map it to the real
	//     join id via `e.data.joins[0].id`; for a multi-join edge there
	//     isn't a single join to pick (that's what the popover is for),
	//     so we don't change selectedJoinId — the popover row's
	//     `data.onSelect(j.id)` is the canonical setter for that case.
	//
	//  2. xyflow reports NO edge selected (pane click, deselect).  Mirror
	//     null into the store.
	$effect(() => {
		const selectedEdge = edges.find((e) => e.selected);
		if (!selectedEdge) {
			if (store.selectedJoinId !== null) store.selectedJoinId = null;
			return;
		}
		 
		const edgeJoins = (selectedEdge.data as any)?.joins as Array<{ id: string }> | undefined;
		if (edgeJoins && edgeJoins.length === 1) {
			const joinId = edgeJoins[0].id;
			if (store.selectedJoinId !== joinId) store.selectedJoinId = joinId;
		}
		// Multi-join edge selected by xyflow but store already has a real
		// join id (popover path) — leave it alone.
	});

	// Auto-flip the column-highlight toggle ON when a single join is
	// selected.  Same UX promise as group-focus: clicking a join is the
	// user saying "show me what this links," and the endpoint column
	// rows are the natural answer.  The user remains free to toggle
	// off mid-focus.
	$effect(() => {
		const id = store.selectedJoinId;
		untrack(() => {
			if (id !== null) store.highlightFocusedColumns = true;
		});
	});

	// Selecting a table on the canvas focuses it — its joins stay vivid,
	// everything else fades. Deselecting (pane click / another selection)
	// clears focus. ONLY selectedTableId is tracked here: untrack the
	// focus-state reads so that focusing via another path (e.g. clicking
	// a group label in the joins panel) doesn't re-fire this effect and
	// clobber the group focus by re-asserting the stale selection.
	//
	// IMPORTANT: skip the auto-focus when pick mode is active. In pick
	// mode every column click auto-selects the parent node (SvelteFlow
	// behaviour); without this guard, the FIRST pick would focus that
	// table and fade everything else, making the rest of the canvas
	// almost un-clickable for the next pick.
	$effect(() => {
		const id = store.selectedTableId;
		const pickMode = store.pickModeActive;
		untrack(() => {
			if (pickMode) {
				// Stay neutral while picking — clear any focus that
				// was lingering from before pick mode started.
				if (store.focusKind !== 'none') store.clearFocus();
				return;
			}
			if (id === null) {
				if (store.focusKind === 'table') store.clearFocus();
			} else {
				if (store.focusKind !== 'table' || store.focusKey !== id) {
					store.focusKind = 'table';
					store.focusKey = id;
				}
			}
		});
	});

	// Arrange state — shared by the toolbar (Arrange button) and the DimSum
	// arrange_canvas tool, so it stays in the shell alongside handleLayout
	// (which reads the canvas region rect). The toolbar owns its own
	// transient toggle-flash + layout-menu state.
	let arranging = $state(false);
	let lastLayoutMode = $state<LayoutMode>('star');

	// Reference to the canvas region div, so the grid layout can fit its
	// column count to the visible area rather than a fixed step-function.
	let canvasRegionEl = $state<HTMLDivElement | null>(null);

	async function handleLayout(mode: LayoutMode) {
		if (store.doc.tables.length === 0) return;
		arranging = true;
		lastLayoutMode = mode;
		try {
			// Pass the user's actual "Show N cols/table" setting so the
			// layout estimates heights from what the canvas RENDERS,
			// not from every column the table has.  Also pass the
			// canvas region's measured dimensions so the grid layout
			// can size its column count to whatever's actually visible.
			const rect = canvasRegionEl?.getBoundingClientRect();
			const next = await applyLayout(store.doc, mode, {
				visibleColumnsCap: store.defaultColumnsShown,
				canvasWidth: rect?.width ?? 0,
				canvasHeight: rect?.height ?? 0
			});
			for (const t of next.tables) {
				store.moveTable(t.id, t.position);
			}
		} finally {
			arranging = false;
		}
	}

	// Joins panel — collapsible right rail. Default OPEN on mount so the
	// "Currently selected" pill never has to compete with the collapsed
	// panel edge for horizontal space (Amelia's report — the pill was
	// floating over the closed panel's header).  User toggle still wins
	// after the initial render.
	let joinsPanelOpen = $state(true);
	// Source-tables sidebar mirrors joinsPanelOpen: closable via an
	// EyeOff button in its header, re-openable via a floating chip on
	// the canvas's top-left corner.
	// Source lives in the LEFT overlay column (CanvasPane), DimSum lives
	// in the bottom half of the RIGHT overlay column beside JOINS. Each
	// has its own EyeOff button; when hidden the parent renders a reopen
	// chip at the panel's natural corner (Source top-left, DimSum below
	// JOINS on the right).
	let sourceVisible = $state(true);
	// #1154 kept DimSum minimized when it sat bottom-LEFT (it ate the
	// left canvas edge before the user opted in). On the right column it
	// is the paired half beside JOINS, so it's visible by default; the
	// EyeOff in the widget header collapses it to a reopen chip.
	let dimSumVisible = $state(true);

	// When Create Joins Mode turns on, force the JOINS panel open so
	// the picks accumulate in a visible pane and the "Match N picks"
	// CTA + auto-name input are reachable.  Turning the mode back off
	// leaves the panel alone (user may want to keep reviewing joins).
	$effect(() => {
		if (store.pickModeActive) {
			untrack(() => {
				joinsPanelOpen = true;
			});
		}
	});

	// Join-creation toast.  The store's `lastJoinFeedback` is a bump
	// counter (`id`) + message; every drag / click / commit that adds a
	// join sets it, batch paths overwrite with a summary.  We watch the
	// `id` (not the message) so re-firing with the same text still shows
	// a fresh toast, and auto-dismiss after 3.5s.
	let joinToast = $state<string | null>(null);
	let joinToastTimer: ReturnType<typeof setTimeout> | null = null;
	$effect(() => {
		const feedback = store.lastJoinFeedback;
		if (!feedback) return;
		untrack(() => {
			joinToast = feedback.message;
			if (joinToastTimer) clearTimeout(joinToastTimer);
			joinToastTimer = setTimeout(() => {
				joinToast = null;
				joinToastTimer = null;
			}, 3500);
		});
	});
	// Join groups drive the toolbar counts, the canvas "Joins (N)" chip, and
	// the JoinsPanel. Grouping is pure — see ./join-groups. The panel owns its
	// own interaction state (select / reorganize / edit / collapse).
	const joinGroups = $derived(computeJoinGroups(store));
	const physicalJoinGroups = $derived(joinGroups.filter((g) => !isSemanticGroup(g)));
	const semanticJoinGroups = $derived(joinGroups.filter((g) => isSemanticGroup(g)));
	const physicalJoinCount = $derived(physicalJoinGroups.reduce((n, g) => n + g.joins.length, 0));
	const semanticJoinCount = $derived(semanticJoinGroups.reduce((n, g) => n + g.joins.length, 0));
</script>

<!-- DimSum sidebar widget — rendered here (the shell owns the chat state)
     and mounted inside the right-side floating column beside JOINS. The
     shell exposes it as a snippet so the JSX below stays legible. -->
{#snippet askDimSumWidget()}
	<AskDimSumWidget
		bind:expanded={dimSumExpanded}
		bind:visible={dimSumVisible}
		messages={aiMessages}
		drafting={aiDrafting}
		bind:intent={aiIntent}
		bind:result={aiResult}
		bind:error={aiError}
		bind:chatOpen={aiChatOpen}
		bind:errorOpen={aiErrorOpen}
		connectionId={store.connectionId}
		fillContainer
		onDraft={() => void runDraftWithAI()}
		onClearChat={clearChat}
		onConfirmResult={() => store.clearPreviousDoc()}
		onCancelResult={() => store.undo()}
	/>
{/snippet}

<div class="flex h-full min-h-0 flex-1 flex-col">
	<CanvasToolbar
		{store}
		{physicalJoinCount}
		{semanticJoinCount}
		{arranging}
		{lastLayoutMode}
		onArrange={handleLayout}
	/>

	<div class="flex flex-1 overflow-hidden">
		<!-- Source + DimSum render as floating cards INSIDE the canvas
		     container (see the `canvas-source-panel-floating` /
		     `canvas-ai-panel-floating` blocks below).  Canvas is full
		     width from the left edge; canvas dots show through wherever
		     the panels/chips aren't sitting. -->

		<DimSumChatDock
			{store}
			messages={aiMessages}
			drafting={aiDrafting}
			error={aiError}
			bind:chatOpen={aiChatOpen}
			bind:errorOpen={aiErrorOpen}
			bind:chatDraft={aiChatDraft}
			onClearChat={clearChat}
			onSend={(draft) => void runDraftWithAI(draft)}
		/>

		<!-- CanvasPane + right-side floating column share a `relative`
		     wrapper so JOINS + DimSum can absolute-overlay the right
		     edge of the canvas, mirroring the way the SOURCE panel
		     floats over the left edge from inside CanvasPane.
		     CanvasPane grows to fill the whole width; the right
		     column is a flex column iterated in fixed order
		     [JOINS, DimSum]. For each entry an OPEN panel takes
		     flex-1 (grows to fill), a CLOSED entry renders a compact
		     reopen chip (auto-height, self-end). Net behaviour:
		     collapsed chips stack at the TOP of the column, panels
		     grow into whatever remains below them, and any empty
		     tail sits safely above the SvelteFlow MiniMap which
		     lives at the canvas's bottom-right. -->
		<div class="relative flex flex-1 overflow-hidden">
			<CanvasPane
				{store}
				bind:nodes
				bind:edges
				{focusActive}
				bind:joinsPanelOpen
				bind:sourceVisible
				bind:dimSumVisible
				bind:canvasRegionEl
				{sourceContext}
			/>
			<div
				class="pointer-events-none absolute top-2 right-2 bottom-2 z-20 flex flex-col gap-2"
				style:width="{store.joinsPanelWidth}px"
				data-testid="canvas-right-column"
			>
				<!-- JOINS: panel (flex-1) when open, chip (auto-height, self-end) when closed. -->
				{#if joinsPanelOpen}
					<div class="flex min-h-0 flex-1 flex-col">
						<JoinsPanel
							{store}
							{joinGroups}
							{physicalJoinGroups}
							{semanticJoinGroups}
							{physicalJoinCount}
							{semanticJoinCount}
							bind:joinsPanelOpen
							onFlushEdges={() => (edges = [])}
						/>
					</div>
				{:else}
					<button
						type="button"
						onclick={() => (joinsPanelOpen = true)}
						class="pointer-events-auto inline-flex h-7 items-center gap-1 self-end rounded border border-input bg-card px-2 text-xs font-medium shadow-sm transition-colors hover:bg-accent hover:text-accent-foreground"
						data-testid="canvas-joins-panel-open"
						title="Show joins panel"
					>
						<ChevronRight class="h-3.5 w-3.5" aria-hidden="true" />
						Joins ({physicalJoinCount})
					</button>
				{/if}
				<!-- DimSum: panel (flex-1) when open, chip (auto-height, self-end) when closed.
				     Closed chip lands directly under the JOINS panel/chip so it never
				     collides with the SvelteFlow MiniMap at the bottom-right of the canvas. -->
				{#if dimSumVisible}
					<div
						class="pointer-events-auto flex min-h-0 flex-1 flex-col"
						data-testid="canvas-ai-panel-floating"
					>
						{@render askDimSumWidget()}
					</div>
				{:else}
					<button
						type="button"
						onclick={() => (dimSumVisible = true)}
						class="pointer-events-auto inline-flex h-7 items-center gap-1 self-end rounded border border-input bg-card px-2 text-xs font-medium shadow-sm transition-colors hover:bg-accent hover:text-accent-foreground"
						data-testid="canvas-ai-panel-open"
						title="Show DimSum"
					>
						<Sparkles class="h-3.5 w-3.5 text-primary" aria-hidden="true" />
						Ask DimSum
					</button>
				{/if}
			</div>
		</div>
	</div>
</div>

{#if joinToast}
	<Toast
		tone="success"
		testid="canvas-join-toast"
		onDismiss={() => {
			joinToast = null;
			if (joinToastTimer) {
				clearTimeout(joinToastTimer);
				joinToastTimer = null;
			}
		}}
	>
		{joinToast}
	</Toast>
{/if}

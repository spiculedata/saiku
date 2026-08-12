/**
 * saiku#1095 — cube-tree hover actions → workspace modal bridge.
 *
 * DimensionList (the cube tree) and QueryCanvas (which owns the axis-filter
 * modals and their save logic) are siblings, so the tree can't open the
 * canvas's modals directly. Duplicating the modal mounts + save handlers in
 * the tree would fork the axis-MDX composition logic — exactly the kind of
 * records/matrix drift that has bitten before. Instead the tree emits a tiny
 * INTENT here and QueryCanvas consumes it, opening its existing modals with
 * the existing handlers. Pure glue: no new modal, no new save path.
 *
 * `seq` makes each request distinct so repeated clicks on the same node
 * re-trigger the consuming effect even when the payload is identical.
 */
export type TreeActionKind = "topcount" | "sort";

export interface TreeActionRequest {
	kind: TreeActionKind;
	/**
	 * `[Measures].[…]` unique name to pre-target, when launched from a measure
	 * node. Undefined when launched from a level node — the modal then lets the
	 * user pick the measure, matching the toolbar-launched behaviour.
	 */
	measureUniqueName?: string;
	seq: number;
}

class TreeActionsStore {
	request = $state<TreeActionRequest | null>(null);
	#seq = 0;

	open(kind: TreeActionKind, measureUniqueName?: string): void {
		this.request = { kind, measureUniqueName, seq: ++this.#seq };
	}

	clear(): void {
		this.request = null;
	}
}

export const treeActions = new TreeActionsStore();

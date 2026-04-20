<script lang="ts">
  /*
   * Left-pane tree view for the schema-generator draft. Purely presentational —
   * all path/label/provenance derivation lives in schemaTree.model.ts so it can
   * be unit-tested without a browser.
   *
   * Renders a nested list keyed by node path. Clicking a node fires onSelect
   * (and updates the bindable selectedPath) so the parent can open the drawer.
   */
  import type { DraftView } from "$lib/api/schemaGen";
  import {
    buildTree,
    provenanceBadgeLabel,
    type TreeNode,
  } from "./schemaTree.model";

  interface Props {
    draft: DraftView | null;
    selectedPath?: string | null;
    onSelect?: (path: string) => void;
  }

  let {
    draft,
    selectedPath = $bindable(null),
    onSelect,
  }: Props = $props();

  const tree = $derived(buildTree(draft));

  function handleSelect(path: string) {
    selectedPath = path;
    onSelect?.(path);
  }
</script>

{#snippet renderNode(node: TreeNode)}
  <li class="tree__item">
    <button
      type="button"
      class="tree__row"
      class:tree__row--selected={selectedPath === node.path}
      data-kind={node.kind}
      data-path={node.path}
      onclick={() => handleSelect(node.path)}
    >
      <span class="tree__label">{node.label}</span>
      {#if node.provenance}
        <span
          class="tree__badge tree__badge--{node.provenance.toLowerCase()}"
          title={node.ruleId ?? provenanceBadgeLabel(node.provenance)}
        >
          {provenanceBadgeLabel(node.provenance)}
        </span>
      {/if}
    </button>
    {#if node.children.length > 0}
      <ul class="tree__children">
        {#each node.children as child (child.path)}
          {@render renderNode(child)}
        {/each}
      </ul>
    {/if}
  </li>
{/snippet}

<nav class="tree" aria-label="Schema draft">
  {#if tree.length === 0}
    <p class="tree__empty">No draft loaded.</p>
  {:else}
    <ul class="tree__root">
      {#each tree as node (node.path)}
        {@render renderNode(node)}
      {/each}
    </ul>
  {/if}
</nav>

<style>
  .tree {
    font-family: var(--font-sans);
    font-size: var(--fs-sm);
    color: var(--fg);
    padding: var(--space-2);
    overflow: auto;
  }
  .tree__empty {
    color: var(--fg-muted);
    padding: var(--space-3);
  }
  .tree__root,
  .tree__children {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .tree__children {
    padding-left: var(--space-4);
    border-left: 1px dashed var(--border);
    margin-left: var(--space-2);
  }
  .tree__item {
    margin: 2px 0;
  }
  .tree__row {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    width: 100%;
    background: transparent;
    border: 0;
    color: inherit;
    text-align: left;
    padding: 3px var(--space-2);
    border-radius: var(--radius-sm);
    cursor: pointer;
    font: inherit;
  }
  .tree__row:hover {
    background: var(--bg-muted);
  }
  .tree__row--selected {
    background: var(--bg-subtle);
    font-weight: 600;
  }
  .tree__row:focus-visible {
    outline: none;
    box-shadow: var(--focus-ring);
  }
  .tree__label {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .tree__badge {
    font-size: 10px;
    line-height: 1;
    padding: 2px 6px;
    border-radius: 999px;
    font-weight: 600;
    letter-spacing: 0.03em;
    text-transform: uppercase;
    color: var(--accent-fg);
    background: var(--fg-subtle);
  }
  .tree__badge--rule {
    background: var(--border-strong);
    color: var(--fg);
  }
  .tree__badge--llm {
    background: var(--accent);
    color: var(--accent-fg);
  }
  .tree__badge--user {
    background: var(--success);
    color: #fff;
  }
</style>

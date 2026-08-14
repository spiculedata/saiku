<!--
  SortableColumnHeader — clickable table column header with sort indicator.

  Replaces 18-line copy-paste blocks per column. Caller owns the sort
  state and the `onToggle` callback; this component just renders the
  label + the appropriate arrow icon.

  Usage:

    <th>
      <SortableColumnHeader
        label="Filename"
        sortKey="filename"
        activeKey={sortKey}
        direction={sortDir}
        onToggle={toggleSort}
      />
    </th>
-->
<script lang="ts" generics="K extends string">
	// Arrows are inlined rather than imported from lucide. This component is
	// published in @concepttocloud/saiku-design-system, and the two consuming
	// apps are on different lucide packages (saiku-ui on `lucide-svelte`,
	// saiku-cloud on `@lucide/svelte`) — importing either would force one app
	// to carry a second icon library for three glyphs.
	//
	// These are lucide's arrow-up / arrow-down / arrow-up-down geometries,
	// with each subpath re-anchored to an ABSOLUTE `M`. lucide ships them as
	// several sibling <path> elements; collapsing those into one `d` makes a
	// leading relative `m` resolve against the previous subpath's end point
	// instead of the origin, which throws the later strokes off the 24×24
	// canvas. Keep the capital M on every subpath.
	const ARROW_UP = 'M5 12l7-7 7 7M12 19V5';
	const ARROW_DOWN = 'M12 5v14M19 12l-7 7-7-7';
	const ARROW_UP_DOWN = 'M21 16l-4 4-4-4M17 20V4M3 8l4-4 4 4M7 4v16';

	type Direction = 'asc' | 'desc';

	interface Props {
		label: string;
		sortKey: K;
		activeKey: K;
		direction: Direction;
		onToggle: (key: K) => void;
		testid?: string;
	}

	let { label, sortKey, activeKey, direction, onToggle, testid }: Props = $props();

	const isActive = $derived(sortKey === activeKey);
</script>

<button
	type="button"
	onclick={() => onToggle(sortKey)}
	class="inline-flex items-center gap-1 hover:text-foreground"
	data-testid={testid ?? `sort-${sortKey}`}
>
	{label}
	{#if isActive}
		{#if direction === 'asc'}
			{@render icon(ARROW_UP, 'h-3 w-3')}
		{:else}
			{@render icon(ARROW_DOWN, 'h-3 w-3')}
		{/if}
	{:else}
		{@render icon(ARROW_UP_DOWN, 'h-3 w-3 opacity-40')}
	{/if}
</button>

{#snippet icon(d: string, cls: string)}
	<svg
		class={cls}
		viewBox="0 0 24 24"
		fill="none"
		stroke="currentColor"
		stroke-width="2"
		stroke-linecap="round"
		stroke-linejoin="round"
		aria-hidden="true"
	>
		<path {d} />
	</svg>
{/snippet}

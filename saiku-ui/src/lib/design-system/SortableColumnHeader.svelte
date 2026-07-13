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
	import { ArrowUp, ArrowDown, ArrowUpDown } from 'lucide-svelte';

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
			<ArrowUp class="h-3 w-3" aria-hidden="true" />
		{:else}
			<ArrowDown class="h-3 w-3" aria-hidden="true" />
		{/if}
	{:else}
		<ArrowUpDown class="h-3 w-3 opacity-40" aria-hidden="true" />
	{/if}
</button>

<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import { SortableColumnHeader } from '$lib/design-system';

	type Key = 'filename' | 'size' | 'uploaded';

	let activeKey = $state<Key>('uploaded');
	let direction = $state<'asc' | 'desc'>('desc');

	function toggle(key: Key) {
		if (activeKey === key) {
			direction = direction === 'asc' ? 'desc' : 'asc';
		} else {
			activeKey = key;
			direction = key === 'uploaded' ? 'desc' : 'asc';
		}
	}

	const { Story } = defineMeta({
		title: 'Compounds/SortableColumnHeader',
		component: SortableColumnHeader,
		tags: ['autodocs']
	});
</script>

<Story name="Interactive table headers">
	<div class="mx-auto max-w-3xl p-8">
		<table class="w-full text-sm">
			<thead class="text-left text-xs text-muted-foreground uppercase">
				<tr>
					<th class="px-4 py-2">
						<SortableColumnHeader
							label="Filename"
							sortKey="filename"
							{activeKey}
							{direction}
							onToggle={toggle}
						/>
					</th>
					<th class="px-4 py-2">
						<SortableColumnHeader
							label="Size"
							sortKey="size"
							{activeKey}
							{direction}
							onToggle={toggle}
						/>
					</th>
					<th class="px-4 py-2">
						<SortableColumnHeader
							label="Uploaded"
							sortKey="uploaded"
							{activeKey}
							{direction}
							onToggle={toggle}
						/>
					</th>
				</tr>
			</thead>
			<tbody>
				<tr class="border-t border-border/40">
					<td class="px-4 py-2 font-mono text-xs">invoice.csv</td>
					<td class="px-4 py-2 text-muted-foreground">248 KB</td>
					<td class="px-4 py-2 text-muted-foreground">2 min ago</td>
				</tr>
				<tr class="border-t border-border/40">
					<td class="px-4 py-2 font-mono text-xs">sales.parquet</td>
					<td class="px-4 py-2 text-muted-foreground">4.8 MB</td>
					<td class="px-4 py-2 text-muted-foreground">1 day ago</td>
				</tr>
			</tbody>
		</table>
		<p class="mt-4 text-xs text-muted-foreground">
			Sorting by <code class="font-mono">{activeKey}</code> ({direction})
		</p>
	</div>
</Story>

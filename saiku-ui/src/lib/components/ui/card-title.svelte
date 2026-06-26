<script lang="ts">
	import type { HTMLAttributes } from 'svelte/elements';
	import { cn } from '$lib/utils';

	let { class: className, children, ...restProps }: HTMLAttributes<HTMLHeadingElement> = $props();
</script>

<h3 class={cn('text-lg leading-none font-semibold tracking-tight', className)} {...restProps}>
	{@render children?.()}
</h3>

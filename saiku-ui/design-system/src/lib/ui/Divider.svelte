<!--
	Divider — a horizontal (default) or vertical rule.  Optional label
	renders inline as an eyebrow ("or", "OTHERWISE").
-->
<script lang="ts">
	let {
		orientation = 'horizontal',
		children
	}: {
		orientation?: 'horizontal' | 'vertical';
		children?: import('svelte').Snippet;
	} = $props();
</script>

{#if orientation === 'vertical'}
	<div
		class="inline-block h-full w-px shrink-0 bg-border"
		role="separator"
		aria-orientation="vertical"
	></div>
{:else if children}
	<div class="flex items-center gap-3" role="separator" aria-orientation="horizontal">
		<div class="h-px flex-1 bg-border"></div>
		<span class="text-[10px] font-medium tracking-wider text-muted-foreground uppercase">
			{@render children()}
		</span>
		<div class="h-px flex-1 bg-border"></div>
	</div>
{:else}
	<div class="h-px w-full bg-border" role="separator" aria-orientation="horizontal"></div>
{/if}

<script lang="ts">
	export interface ContextMenuItem {
		id: string;
		label: string;
		disabled?: boolean;
		danger?: boolean;
		sep?: boolean;
	}

	interface Props {
		open: boolean;
		x: number;
		y: number;
		items: ContextMenuItem[];
		onPick: (id: string) => void;
		onClose: () => void;
	}

	let { open, x, y, items, onPick, onClose }: Props = $props();

	function onDocClick(e: MouseEvent) {
		if (!open) return;
		const target = e.target as Node | null;
		const host = document.querySelector(".ctx-menu[data-open='true']");
		if (host && target && host.contains(target)) return;
		onClose();
	}
</script>

<svelte:window onclick={onDocClick} oncontextmenu={onDocClick} />

{#if open}
	<div class="ctx-menu" data-open="true" role="menu" style="left:{x}px;top:{y}px">
		{#each items as it}
			{#if it.sep}
				<div class="ctx-menu__sep" aria-hidden="true"></div>
			{:else}
				<button
					type="button"
					class="ctx-menu__item"
					class:danger={it.danger}
					disabled={it.disabled}
					onclick={() => {
						onPick(it.id);
						onClose();
					}}>{it.label}</button
				>
			{/if}
		{/each}
	</div>
{/if}

<style>
	.ctx-menu {
		position: fixed;
		min-width: 180px;
		background: hsl(var(--bg));
		border: 1px solid hsl(var(--border));
		border-radius: 6px;
		box-shadow: var(--shadow-lg);
		padding: 4px;
		z-index: 1000;
		font-size: var(--fs-sm);
		animation: saiku-panel-in var(--duration-fast) ease;
	}
	.ctx-menu__item {
		display: block;
		width: 100%;
		text-align: left;
		padding: 6px 10px;
		background: transparent;
		border: 0;
		color: hsl(var(--fg));
		font: inherit;
		cursor: pointer;
		border-radius: 4px;
	}
	.ctx-menu__item:hover:not(:disabled) {
		background: hsl(var(--bg-subtle));
	}
	.ctx-menu__item:disabled {
		color: hsl(var(--fg-subtle));
		cursor: default;
	}
	.ctx-menu__item.danger {
		color: hsl(var(--danger));
	}
	.ctx-menu__sep {
		height: 1px;
		background: hsl(var(--border));
		margin: 3px 0;
	}
</style>

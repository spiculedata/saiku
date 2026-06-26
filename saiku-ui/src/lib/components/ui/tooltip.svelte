<!--
  Tooltip — one-shot wrapper around bits-ui's Tooltip primitive.

  Composes Provider + Root + Trigger + Content into a single
  component so most call sites collapse to:

    <Tooltip text="Open command palette">
      <button>…</button>
    </Tooltip>

  Earlier this wrapper rendered `<TooltipPrimitive.Trigger>{children}</...>`
  which produced `<button><button>...</button></button>` — invalid HTML
  + the outer button's default styling bled through (the search trigger
  in the sidebar rendered with a pink background, see bug repro
  2026-05-23). Now uses bits-ui's `child` snippet pattern: the trigger
  doesn't emit its own DOM element, instead spreading its event +
  ARIA props onto a layout-invisible wrapper span (class="contents")
  that lives directly around the user's element. The user's button
  stays the SINGLE button and keeps its own styling intact.

  The default delay (300ms) is snappier than bits-ui's 700ms default.
  Override via `delayMs` when a slower hint is warranted (e.g. on a
  destructive action where we want intent).

  For richer compositions (controlled-open state, custom content,
  portal target), use the sub-components directly via
  `import { Tooltip as TooltipPrimitive } from 'bits-ui'`.
-->
<script lang="ts">
	import { Tooltip as TooltipPrimitive } from 'bits-ui';
	import type { Snippet } from 'svelte';

	interface Props {
		/** The hint text. Empty / null disables the tooltip. */
		text?: string | null;
		/** Open delay in ms. Default 300. */
		delayMs?: number;
		/** Tooltip placement relative to the trigger. */
		side?: 'top' | 'right' | 'bottom' | 'left';
		/** Trigger element — receives the trigger event + ARIA props via a `contents` span. */
		children: Snippet;
	}

	let { text = null, delayMs = 300, side = 'top', children }: Props = $props();
</script>

{#if text}
	<TooltipPrimitive.Provider delayDuration={delayMs}>
		<TooltipPrimitive.Root>
			<TooltipPrimitive.Trigger>
				{#snippet child({ props })}
					<!--
						`class="contents"` makes this span layout-invisible:
						its child (the user's button/anchor/whatever) lays out
						as if the span weren't there. The trigger's hover +
						focus + ARIA props attach to the span itself, so the
						user's element stays untouched + keeps its own styles.
						No nested <button>; no leaked outer styling.
					-->
					<span {...props} class="contents">
						{@render children()}
					</span>
				{/snippet}
			</TooltipPrimitive.Trigger>
			<TooltipPrimitive.Portal>
				<TooltipPrimitive.Content
					{side}
					sideOffset={6}
					class="z-50 max-w-xs rounded-md border border-border bg-popover px-2.5 py-1.5 text-xs leading-snug text-popover-foreground shadow-md"
				>
					{text}
				</TooltipPrimitive.Content>
			</TooltipPrimitive.Portal>
		</TooltipPrimitive.Root>
	</TooltipPrimitive.Provider>
{:else}
	{@render children()}
{/if}

<script lang="ts">
	/**
	 * Modal — focus-trapped dialog with backdrop, panel, header, body, footer.
	 *
	 * Reused 25+ times across the codebase (every saved-query / drillthrough
	 * / chart-editor / format / permissions modal etc.). Behaviour is
	 * load-bearing:
	 *
	 *   - Focus moves into the panel on open, lands on the first focusable
	 *     that ISN'T the × close button so users hit a useful target.
	 *   - Tab / Shift+Tab wrap inside the panel (focus trap).
	 *   - Escape calls onClose (stops propagation so nested handlers
	 *     don't see it twice).
	 *   - Focus restores to the previously-focused element on close.
	 *
	 * Visual layer (styling) is Tailwind utility classes resolving through
	 * the @theme bridge (bg-bg-overlay → var(--bg-overlay), etc.).
	 * Two custom animation classes (.saiku-overlay-in / .saiku-panel-in)
	 * reference @keyframes defined globally in app.css — those stay vanilla
	 * CSS because @keyframes don't compose cleanly with Tailwind 4's
	 * arbitrary-value escape hatch.
	 */
	import type { Snippet } from 'svelte';
	import { tick } from 'svelte';
	import { X } from '@lucide/svelte';
	import { Button } from '$lib/components/ui';

	interface Props {
		title: string;
		open: boolean;
		size?: 'sm' | 'md' | 'lg' | 'xl';
		onClose: () => void;
		children?: Snippet;
		footer?: Snippet;
	}

	let { title, open, size = 'md', onClose, children, footer }: Props = $props();

	let panelEl = $state<HTMLElement | null>(null);
	let previouslyFocused: HTMLElement | null = null;
	// Unique id for aria-labelledby (module-level counter; ok in the browser).
	const titleId = `saiku-modal-title-${Math.random().toString(36).slice(2, 9)}`;

	// Tailwind doesn't tree-shake conditional class strings, so we map the
	// size prop to a literal lookup. Each entry is a separate class string
	// so PurgeCSS sees every one in the source.
	const sizeClass = {
		sm: 'w-[360px]',
		md: 'w-[520px]',
		lg: 'w-[760px]',
		xl: 'w-[960px]'
	} as const;

	function focusableIn(root: HTMLElement): HTMLElement[] {
		const nodes = root.querySelectorAll<HTMLElement>(
			'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
		);
		return Array.from(nodes).filter((el) => !el.hasAttribute('inert') && el.offsetParent !== null);
	}

	$effect(() => {
		if (!open) return;
		previouslyFocused = (document.activeElement as HTMLElement | null) ?? null;
		// Wait a tick for the panel to mount, then focus the first focusable
		// that's NOT the "×" close button so users land on something useful.
		void tick().then(() => {
			if (!panelEl) return;
			const items = focusableIn(panelEl);
			const preferred = items.find((el) => !el.dataset.modalClose);
			(preferred ?? items[0] ?? panelEl).focus();
		});
		return () => {
			// Restore focus to whatever was focused before the modal opened.
			// Use a rAF so the element is visible/focusable again after teardown.
			const prev = previouslyFocused;
			previouslyFocused = null;
			if (prev && typeof prev.focus === 'function') {
				queueMicrotask(() => prev.focus());
			}
		};
	});

	function onKeyDown(e: KeyboardEvent) {
		if (!open) return;
		if (e.key === 'Escape') {
			e.stopPropagation();
			onClose();
			return;
		}
		if (e.key !== 'Tab' || !panelEl) return;
		const items = focusableIn(panelEl);
		if (items.length === 0) {
			e.preventDefault();
			panelEl.focus();
			return;
		}
		const first = items[0];
		const last = items[items.length - 1];
		const active = document.activeElement as HTMLElement | null;
		if (e.shiftKey) {
			if (active === first || !panelEl.contains(active)) {
				e.preventDefault();
				last.focus();
			}
		} else {
			if (active === last) {
				e.preventDefault();
				first.focus();
			}
		}
	}
</script>

{#if open}
	<div
		class="fixed inset-0 z-[1000] grid place-items-center"
		role="dialog"
		tabindex="-1"
		aria-modal="true"
		aria-labelledby={titleId}
		onkeydown={onKeyDown}
	>
		<button
			type="button"
			class="saiku-overlay-in absolute inset-0 cursor-pointer border-0 bg-bg-overlay"
			aria-label="Close dialog"
			tabindex="-1"
			onclick={onClose}
		></button>
		<div
			class="saiku-panel-in relative flex max-h-[calc(100vh-48px)] min-w-[320px] flex-col overflow-hidden rounded-lg border border-border bg-bg text-fg shadow-lg focus:outline-none {sizeClass[
				size
			]}"
			bind:this={panelEl}
			tabindex="-1"
		>
			<header class="flex items-center justify-between gap-3 border-b border-border px-5 py-4">
				<h2 class="m-0 text-lg font-semibold" id={titleId}>
					{title}
				</h2>
				<Button
					variant="ghost"
					size="icon"
					class="h-7 w-7"
					aria-label="Close"
					data-modal-close="true"
					onclick={onClose}
				>
					<X size={16} />
				</Button>
			</header>
			<div class="overflow-auto p-5">
				{@render children?.()}
			</div>
			{#if footer}
				<footer class="flex justify-end gap-2 border-t border-border bg-bg-muted px-5 py-4">
					{@render footer()}
				</footer>
			{/if}
		</div>
	</div>
{/if}

<style>
	/* Animation hooks for the @keyframes defined in app.css. Tailwind 4's
     arbitrary-value escape `animate-[saiku-overlay-in_120ms_ease]` would
     work too but the named classes here are clearer in DevTools. */
	.saiku-overlay-in {
		animation: saiku-overlay-in var(--duration-fast) ease;
	}
	.saiku-panel-in {
		animation: saiku-panel-in var(--duration-normal) ease;
	}
</style>

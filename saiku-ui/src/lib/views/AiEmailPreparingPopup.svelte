<script lang="ts">
	/*
	 * "Preparing your email…" popup — a small centered loader shown on the
	 * query-maker page during the AI email NL-draft hand-off, replaced by the
	 * email composer modal when it opens. Driven by the emailComposer store and
	 * mounted at the page level (next to EmailMeThisModal) ABOVE / OUTSIDE the AI
	 * drawer, so a drawer unmount/remount can't hide it mid-hand-off.
	 *
	 * Consistent with the app's Modal chrome (bg-bg-overlay backdrop, bordered
	 * card, shadow) but deliberately lighter than the full Modal primitive: this
	 * is a transient, non-dismissable loader, so it carries no close button /
	 * focus trap. No @html.
	 */
	import { emailComposer } from '$lib/stores/emailComposer.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { Loader2 } from '@lucide/svelte';
</script>

{#if emailComposer.preparing}
	<div
		class="fixed inset-0 z-[1100] grid place-items-center bg-bg-overlay p-4"
		role="status"
		aria-live="polite"
	>
		<div
			class="flex items-center gap-3 rounded-lg border border-border bg-bg px-6 py-4 text-fg shadow-lg"
		>
			<Loader2 size={20} class="animate-spin text-primary" aria-hidden="true" />
			<span class="text-sm font-medium">
				{i18n.t('workspace.aiEmail.preparing', 'Preparing your email…')}
			</span>
		</div>
	</div>
{/if}

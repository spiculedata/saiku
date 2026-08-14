<!--
  Toast — transient top-right notification with manual dismiss.

  Tone-driven via the design-system tokens. The component is purely
  presentational — caller owns the timeout / show / dismiss lifecycle.

  Typical pattern:

    let toastMessage = $state<string | null>(null);
    function showToast(msg: string) {
      toastMessage = msg;
      setTimeout(() => { toastMessage = null; }, 4500);
    }

    {#if toastMessage}
      <Toast tone="success" onDismiss={() => toastMessage = null}>
        {toastMessage}
      </Toast>
    {/if}
-->
<script lang="ts">
	import type { Snippet } from 'svelte';
	import { TONE_CLASSES, type Tone } from './tokens';

	interface Props {
		tone?: Tone;
		testid?: string;
		dismissTestid?: string;
		onDismiss?: () => void;
		children: Snippet;
	}

	let { tone = 'success', testid, dismissTestid, onDismiss, children }: Props = $props();

	const toneClass = $derived(TONE_CLASSES[tone]);
	// Icon character per tone. Tiny visual rhetoric.
	const icon = $derived(
		tone === 'success' ? '✓' : tone === 'error' ? '✗' : tone === 'warning' ? '!' : 'ℹ'
	);
</script>

<div
	class="fixed top-6 right-6 z-50 flex max-w-sm items-start gap-3 rounded-lg border px-4 py-3 text-sm shadow-lg backdrop-blur {toneClass}"
	role="status"
	aria-live="polite"
	data-testid={testid}
>
	<span aria-hidden="true" class="mt-0.5 font-bold">{icon}</span>
	<span class="flex-1 leading-snug">{@render children()}</span>
	{#if onDismiss}
		<button
			type="button"
			class="opacity-70 hover:opacity-100"
			aria-label="Dismiss"
			data-testid={dismissTestid}
			onclick={onDismiss}
		>
			×
		</button>
	{/if}
</div>

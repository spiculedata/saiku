<script lang="ts">
	/*
	 * A single suggestion card — target path, before → after preview, a
	 * confidence-tier pill, the rationale, and Accept / Reject buttons.
	 *
	 * Purely presentational; all derivation (tier bucketing, the before / after
	 * strings) lives in suggestionsFeed.model.ts so it can be unit-tested.
	 */
	import { CheckCircle, XCircle } from '@lucide/svelte';

	import { confidenceTier, describeOp, type FeedSuggestionOp } from './suggestionsFeed.model';

	interface Props {
		op: FeedSuggestionOp;
		onAccept: () => void;
		onReject: () => void;
	}

	let { op, onAccept, onReject }: Props = $props();

	const tier = $derived(confidenceTier(op));
	const described = $derived(describeOp(op));
</script>

<article class="card" data-op={op.op} data-tier={tier}>
	<header class="flex items-center justify-between gap-2">
		<code class="card__path">{op.targetPath}</code>
		<span
			class="card__tier card__tier--{tier}"
			title="Confidence {(op.confidence * 100).toFixed(0)}%"
		>
			{tier}
		</span>
	</header>

	<div class="flex flex-wrap items-center gap-2">
		<span class="card__before">{described.before}</span>
		<span class="text-fg-muted" aria-hidden="true">→</span>
		<span class="font-semibold">{described.after}</span>
	</div>

	<p class="m-0 text-xs text-fg-muted">{described.rationale}</p>

	<footer class="flex justify-end gap-2">
		<button
			type="button"
			class="card__btn card__btn--accept"
			onclick={onAccept}
			aria-label="Accept suggestion"
		>
			<CheckCircle size={14} />
			<span>Accept</span>
		</button>
		<button
			type="button"
			class="card__btn text-fg-muted"
			onclick={onReject}
			aria-label="Reject suggestion"
		>
			<XCircle size={14} />
			<span>Reject</span>
		</button>
	</footer>
</article>

<style>
	.card {
		display: flex;
		flex-direction: column;
		gap: var(--space-2);
		padding: var(--space-3);
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		background: hsl(var(--bg));
		font-size: var(--fs-sm);
	}
	.card__path {
		font-family: var(--font-mono);
		font-size: var(--fs-xs);
		color: hsl(var(--fg-muted));
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.card__tier {
		font-size: 10px;
		line-height: 1;
		padding: 3px 8px;
		border-radius: 999px;
		font-weight: var(--weight-semibold);
		letter-spacing: 0.03em;
		text-transform: uppercase;
		color: hsl(var(--primary-foreground));
		background: hsl(var(--fg-subtle));
	}
	.card__tier--high {
		background: hsl(var(--success));
		color: #fff;
	}
	.card__before {
		color: hsl(var(--fg-muted));
		text-decoration: line-through;
	}
	.card__btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font: inherit;
		font-size: var(--fs-xs);
		padding: 4px var(--space-3);
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		background: hsl(var(--bg));
		color: hsl(var(--fg));
		cursor: pointer;
	}
	.card__btn:hover {
		background: hsl(var(--bg-muted));
	}
	.card__btn:focus-visible {
		outline: none;
		box-shadow: var(--focus-ring);
	}
	.card__btn--accept {
		color: hsl(var(--success));
		border-color: hsl(var(--success));
	}
	.card__btn--accept:hover {
		background: hsl(var(--success));
		color: #fff;
	}
</style>

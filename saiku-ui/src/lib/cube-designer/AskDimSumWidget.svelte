<!--
  Ask DimSum floating widget — the schema canvas's inline AI chat.

  Lifts the whole widget out of SchemaCanvasView so that giant file
  isn't the only place the chat DOM lives.  All state lives on the
  parent (persisted per connection); this component just binds the
  reactive bag it needs and calls back for the two side-effectful
  actions (draft new turn, clear history).

  Two collapse controls:
    - `expanded` toggles the body (chat log + input) into a header-only
      strip.  Chevron in the header.
    - `visible` (bindable) is the "hide entirely" state — EyeOff in the
      header sets it false; the parent handles the reopen chip.
-->
<script lang="ts">
	import {
		ChevronDown,
		ChevronRight,
		Sparkles,
		EyeOff,
		Maximize2,
		Trash2,
		AlertTriangle,
		Loader2,
		X
	} from '@lucide/svelte';
	import type { AnthropicBlock, ChatMessage, DimSumMutationResult } from './ai-chat-types';

	interface Props {
		/** Body-visible state.  `true` = full chat panel, `false` = just
		 *  the header row.  Bindable so the parent can honour the widget's
		 *  chevron toggle. */
		expanded: boolean;
		/** Whole-widget visibility.  Setting `false` (via the EyeOff in
		 *  the header) hides the widget entirely; the parent's reopen
		 *  chip flips it back to `true`. */
		visible: boolean;
		/** Rolling conversation.  Read-only from the widget's side. */
		messages: ChatMessage[];
		/** True while a draft is in flight — disables the input + button. */
		drafting: boolean;
		/** Freeform user intent bound to the textarea. */
		intent: string;
		/** Mutation-summary banner state.  Cleared by Confirm/Cancel. */
		result: DimSumMutationResult | null;
		/** Error banner state.  Cleared by the Dismiss ✕. */
		error: string | null;
		/** Whether the popped-out chat modal is open. */
		chatOpen: boolean;
		/** Whether the full-text error modal is open. */
		errorOpen: boolean;
		/** Non-null when a source is connected; null disables the Send
		 *  button with a "Pick a connection first" tooltip. */
		connectionId: string | null;
		/** When true, the widget fills its parent's height (used inside the
		 *  right-side canvas column where JOINS + DimSum share a flex column).
		 *  Overrides the fixed 420px expanded height and drops `shrink-0` so
		 *  the flex parent can bound it. */
		fillContainer?: boolean;

		onDraft: () => void;
		onClearChat: () => void;
		/** Confirm a mutation-result banner: clear the previous doc and
		 *  wipe the banner. */
		onConfirmResult: () => void;
		/** Cancel a mutation-result banner: undo the mutation and wipe
		 *  the banner. */
		onCancelResult: () => void;
	}

	// `$bindable()` is a compiler macro, not a plain assignment — the eslint
	// `no-useless-assignment` rule doesn't know that.  Disable per-line for
	// the three props whose next mutation is in a nested handler (visible,
	// chatOpen, errorOpen) so the rule doesn't false-positive.
	let {
		expanded = $bindable(),
		 
		visible = $bindable(),
		messages,
		drafting,
		intent = $bindable(),
		result = $bindable(),
		error = $bindable(),
		 
		chatOpen = $bindable(),
		 
		errorOpen = $bindable(),
		connectionId,
		fillContainer = false,
		onDraft,
		onClearChat,
		onConfirmResult,
		onCancelResult
	}: Props = $props();

	// In fillContainer mode the widget IS the panel — the collapse-to-header
	// state was for the old bottom-left placement (#1154) where it ate the
	// canvas edge before the user opted in. When we're the paired half beside
	// JOINS in the right column, expanded is the only useful state; forcing it
	// keeps the reopen-chip → click round-trip landing on a live body instead
	// of an empty header (reported 2026-07-30).
	const bodyOpen = $derived(fillContainer ? true : expanded);

	function toggleBody() {
		if (fillContainer) return;
		expanded = !expanded;
	}

	let logEl = $state<HTMLDivElement | null>(null);
	// Auto-scroll the log to the bottom on new messages / drafting flip.
	$effect(() => {
		if (!bodyOpen) return;
		void messages.length;
		void drafting;
		queueMicrotask(() => {
			if (logEl) logEl.scrollTop = logEl.scrollHeight;
		});
	});
</script>

<div
	class="flex flex-col rounded border border-border bg-card {fillContainer
		? 'h-full min-h-0 flex-1'
		: 'shrink-0'}"
	data-testid="canvas-ai-section"
	style:height={fillContainer ? undefined : expanded ? '420px' : 'auto'}
>
	<!-- Wrapper is a div (not a button) so it can host the nested Hide /
	     Pop-out / Clear buttons without invalid <button> nesting.  A
	     `role="button"` + keydown handles the collapse toggle. In
	     fillContainer mode the body never collapses, so the wrapper drops
	     its button semantics + cursor + chevron. -->
	<div
		role={fillContainer ? undefined : 'button'}
		tabindex={fillContainer ? undefined : 0}
		onclick={fillContainer ? undefined : toggleBody}
		onkeydown={fillContainer
			? undefined
			: (e) => {
					if (e.key === 'Enter' || e.key === ' ') {
						e.preventDefault();
						toggleBody();
					}
				}}
		class="flex shrink-0 items-center gap-1.5 px-3 py-2 text-left text-[11px] font-semibold tracking-wide text-muted-foreground uppercase {fillContainer
			? ''
			: 'cursor-pointer hover:bg-accent/40'}"
		data-testid="canvas-ai-section-toggle"
		aria-expanded={bodyOpen}
	>
		{#if !fillContainer}
			{#if bodyOpen}
				<ChevronDown class="h-3.5 w-3.5" aria-hidden="true" />
			{:else}
				<ChevronRight class="h-3.5 w-3.5" aria-hidden="true" />
			{/if}
		{/if}
		<Sparkles class="h-3.5 w-3.5 text-primary" aria-hidden="true" />
		<span class="flex-1">Ask DimSum</span>
		<button
			type="button"
			onclick={(e) => {
				e.stopPropagation();
				visible = false;
			}}
			class="rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
			aria-label="Hide DimSum panel"
			title="Hide DimSum"
			data-testid="canvas-ai-panel-hide"
		>
			<EyeOff class="h-3 w-3" aria-hidden="true" />
		</button>
		{#if bodyOpen}
			<button
				type="button"
				onclick={(e) => {
					e.stopPropagation();
					chatOpen = true;
				}}
				class="rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
				aria-label="Pop out to expanded chat"
				title="Pop out to a bigger side-panel chat"
				data-testid="canvas-ai-chat-open"
			>
				<Maximize2 class="h-3 w-3" aria-hidden="true" />
			</button>
			{#if messages.length > 0}
				<button
					type="button"
					onclick={(e) => {
						e.stopPropagation();
						onClearChat();
					}}
					class="rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
					aria-label="Clear chat"
					title="Clear conversation"
				>
					<Trash2 class="h-3 w-3" aria-hidden="true" />
				</button>
			{/if}
		{:else if messages.length > 0}
			<span class="text-[10px] font-normal text-muted-foreground normal-case">
				{messages.length} turn{messages.length === 1 ? '' : 's'}
			</span>
		{/if}
	</div>

	{#if bodyOpen}
		<!-- Message log — same rendering path as the pop-out side panel. -->
		<div
			bind:this={logEl}
			class="min-h-0 flex-1 space-y-2 overflow-y-auto px-3 py-2"
			data-testid="canvas-ai-sidebar-log"
		>
			{#if messages.length === 0 && !drafting}
				<p class="text-[11px] leading-snug text-muted-foreground">
					Ask about your schema — e.g. <em>"what tables can I use?"</em> or request changes like
					<em>"add a join between sales_fact.customer_id and customer.customer_id"</em>.
				</p>
			{/if}
			{#each messages as m, i (i)}
				{@const blocks = (
					typeof m.content === 'string' ? [{ type: 'text', text: m.content }] : m.content
				) as AnthropicBlock[]}
				{@const isToolResultTurn =
					m.role === 'user' &&
					Array.isArray(m.content) &&
					(m.content as AnthropicBlock[]).every((b) => b.type === 'tool_result')}
				{#if isToolResultTurn}
					<div class="flex flex-wrap gap-1" data-testid="canvas-ai-sidebar-toolresults">
						{#each blocks as b, bi (bi)}
							{#if b.type === 'tool_result'}
								<span
									class="rounded border border-border bg-muted/30 px-1.5 py-0.5 text-[9px] {b.is_error
										? 'text-destructive'
										: 'text-success'}"
								>
									{b.is_error ? '✗' : '✓'} result
								</span>
							{/if}
						{/each}
					</div>
				{:else}
					<div
						class="flex {m.role === 'user' ? 'justify-end' : 'justify-start'}"
						data-testid="canvas-ai-sidebar-message"
						data-role={m.role}
					>
						<div
							class="max-w-full rounded-lg px-2 py-1.5 text-[11px] leading-snug {m.role === 'user'
								? 'bg-primary text-primary-foreground'
								: 'border border-border bg-muted/40 text-foreground'}"
						>
							{#each blocks as b, bi (bi)}
								{#if b.type === 'text' && b.text}
									<p class="whitespace-pre-wrap">{b.text}</p>
								{:else if b.type === 'tool_use'}
									<div
										class="my-0.5 inline-flex items-center gap-1 rounded border border-primary/40 bg-primary/5 px-1 py-0.5 font-mono text-[9px] text-primary"
									>
										<Sparkles class="h-2 w-2" aria-hidden="true" />
										{b.name}
									</div>
								{/if}
							{/each}
						</div>
					</div>
				{/if}
			{/each}
			{#if drafting}
				<div class="flex justify-start">
					<div
						class="rounded-lg border border-border bg-muted/40 px-2 py-1 text-[11px] text-muted-foreground"
					>
						<Loader2 class="mr-1 inline h-2.5 w-2.5 animate-spin" aria-hidden="true" />
						Working…
					</div>
				</div>
			{/if}
		</div>

		{#if result}
			<div class="shrink-0 border-t border-primary/30 bg-primary/5 px-3 py-2 text-[10px]">
				<div class="mb-1 flex items-center justify-between gap-1">
					<span class="font-semibold text-primary">
						DimSum changed the canvas
						{#if result.tables !== 0}
							· {result.tables > 0 ? '+' : ''}{result.tables} table{Math.abs(result.tables) === 1
								? ''
								: 's'}
						{/if}
						{#if result.joins !== 0}
							· {result.joins > 0 ? '+' : ''}{result.joins} join{Math.abs(result.joins) === 1
								? ''
								: 's'}
						{/if}
					</span>
				</div>
				<div class="flex gap-1">
					<button
						type="button"
						onclick={() => {
							onConfirmResult();
							result = null;
						}}
						class="flex-1 rounded bg-primary px-2 py-1 text-[10px] font-semibold tracking-wider text-primary-foreground uppercase hover:bg-primary/90"
						data-testid="canvas-ai-sidebar-confirm"
					>
						Confirm
					</button>
					<button
						type="button"
						onclick={() => {
							onCancelResult();
							result = null;
						}}
						class="flex-1 rounded border border-border bg-background px-2 py-1 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase hover:border-primary hover:text-primary"
						data-testid="canvas-ai-sidebar-cancel"
					>
						Cancel
					</button>
				</div>
			</div>
		{/if}

		{#if error}
			<div
				class="flex shrink-0 items-center gap-2 border-t border-destructive/40 bg-destructive/10 px-3 py-1.5 text-[11px] text-destructive"
				data-testid="canvas-ai-error"
			>
				<AlertTriangle class="h-3 w-3 shrink-0" aria-hidden="true" />
				<span class="flex-1 truncate font-medium">1 issue</span>
				<button
					type="button"
					onclick={() => (errorOpen = true)}
					class="shrink-0 rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-wider uppercase hover:bg-destructive/20"
					data-testid="canvas-ai-error-open"
				>
					See →
				</button>
				<button
					type="button"
					onclick={() => (error = null)}
					class="shrink-0 rounded p-0.5 hover:bg-destructive/20"
					aria-label="Dismiss"
					title="Dismiss"
					data-testid="canvas-ai-error-dismiss"
				>
					<X class="h-3 w-3" aria-hidden="true" />
				</button>
			</div>
		{/if}

		<div class="flex shrink-0 items-end gap-1.5 border-t border-border p-2">
			<textarea
				bind:value={intent}
				onkeydown={(e) => {
					if (e.key === 'Enter' && !e.shiftKey) {
						e.preventDefault();
						onDraft();
					}
				}}
				placeholder="Ask DimSum about your schema… (Enter to send)"
				rows="2"
				maxlength="2000"
				disabled={drafting}
				class="min-h-0 flex-1 resize-none rounded border border-input bg-background px-2 py-1 text-[11px] leading-snug placeholder:text-muted-foreground focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none disabled:opacity-60"
				data-testid="canvas-ai-intent"
			></textarea>
			<button
				type="button"
				onclick={() => onDraft()}
				disabled={drafting || !connectionId || !intent.trim()}
				class="inline-flex h-7 shrink-0 items-center gap-1 rounded bg-primary px-2 text-[11px] font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
				data-testid="canvas-ai-draft"
				title={!connectionId
					? 'Pick a connection first'
					: !intent.trim()
						? 'Type something first'
						: 'Send to DimSum'}
			>
				{#if drafting}
					<Loader2 class="h-3 w-3 animate-spin" aria-hidden="true" />
				{:else}
					<Sparkles class="h-3 w-3" aria-hidden="true" />
					Send
				{/if}
			</button>
		</div>
	{/if}
</div>

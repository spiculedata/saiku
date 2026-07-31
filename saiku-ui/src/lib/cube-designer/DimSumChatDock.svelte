<!--
  DimSum overlays for the schema canvas:
    - the full-text AI error modal ("See issue")
    - the popped-out persistent chat dock (rolling multi-turn conversation)

  Presentational: the conversation state + the agent loop live in the parent
  SchemaCanvasView; this component renders them and reports user intent back
  via `onSend` / `onClearChat` + two-way `bind:` props. Owns only the
  chat-log scroll ref + its auto-scroll-to-bottom effect.
-->
<script lang="ts">
	import { Sparkles, AlertTriangle, X, Loader2 } from 'lucide-svelte';
	import type { SchemaCanvasStore } from './state.svelte.js';
	import type { AnthropicBlock, ChatMessage } from './ai-chat-types';

	interface Props {
		store: SchemaCanvasStore;
		messages: ChatMessage[];
		drafting: boolean;
		error: string | null;
		chatOpen: boolean;
		errorOpen: boolean;
		chatDraft: string;
		onClearChat: () => void;
		onSend: (draft: string) => void;
	}

	let {
		store,
		messages,
		drafting,
		error,
		chatOpen = $bindable(),
		errorOpen = $bindable(),
		chatDraft = $bindable(),
		onClearChat,
		onSend
	}: Props = $props();

	// Chat-log DOM ref for auto-scroll to bottom on new messages / on chat
	// modal open. Behaves like claude.ai — replies keep the log pinned at the
	// latest turn without the user manually scrolling.
	let aiChatLogEl = $state<HTMLDivElement | null>(null);
	$effect(() => {
		if (!chatOpen) return;
		// Depend on messages.length + drafting flag so the effect fires both
		// when a new turn lands AND when the "Working…" indicator toggles.
		void messages.length;
		void drafting;
		queueMicrotask(() => {
			if (aiChatLogEl) aiChatLogEl.scrollTop = aiChatLogEl.scrollHeight;
		});
	});
</script>

<!-- Full-text error modal — the compact "1 issue" bar in the AI
     section is where you click "See issue" to get here.  Payload
     is the raw error text (API 4xx body, network msg, etc.) with
     a Copy button so you can paste it into a bug report. -->
{#if errorOpen && error}
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-background/80 p-6"
		role="dialog"
		aria-modal="true"
		aria-labelledby="canvas-ai-error-modal-title"
	>
		<div
			class="flex h-full max-h-[70vh] w-full max-w-2xl flex-col rounded-lg border border-destructive/40 bg-card p-4 shadow-xl"
		>
			<header class="mb-2 flex items-center justify-between gap-2">
				<h2
					id="canvas-ai-error-modal-title"
					class="flex items-center gap-1.5 text-sm font-semibold text-destructive"
				>
					<AlertTriangle class="h-4 w-4" aria-hidden="true" />
					AI issue
				</h2>
				<div class="flex items-center gap-1.5">
					<button
						type="button"
						onclick={() => navigator.clipboard?.writeText(error ?? '')}
						class="rounded border border-border px-2 py-1 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent hover:text-accent-foreground"
						title="Copy the full error text"
						data-testid="canvas-ai-error-copy"
					>
						Copy
					</button>
					<button
						type="button"
						onclick={() => (errorOpen = false)}
						class="rounded p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
						aria-label="Close"
					>
						<X class="h-4 w-4" aria-hidden="true" />
					</button>
				</div>
			</header>
			<pre
				class="min-h-0 flex-1 overflow-auto rounded border border-border bg-muted/40 p-3 font-mono text-[11px] leading-relaxed whitespace-pre-wrap"
				data-testid="canvas-ai-error-full">{error}</pre>
		</div>
	</div>
{/if}

<!-- Persistent chat modal — full rolling multi-turn conversation
     with Claude about the current canvas.  History persists per
     connection via localStorage; Clear chat wipes it. Alternating
     user/assistant bubbles mimic a claude.ai-style thread; input
     bar at the bottom with Enter-to-send (Shift+Enter newline). -->
{#if chatOpen}
	<!-- Side-panel dock instead of a full-screen overlay — the
	     canvas stays visible + interactive on the left, DimSum
	     conversation lives on the right.  Non-modal so the user
	     can still drag tables around while a reply is in flight. -->
	<div
		class="pointer-events-none fixed inset-0 z-40 flex justify-end"
		aria-labelledby="canvas-ai-chat-title"
	>
		<div
			class="pointer-events-auto flex h-full w-full max-w-md flex-col border-l border-border bg-card shadow-xl"
			role="dialog"
			aria-labelledby="canvas-ai-chat-title"
		>
			<header
				class="flex shrink-0 items-center justify-between gap-2 border-b border-border px-4 py-3"
			>
				<h2 id="canvas-ai-chat-title" class="flex items-center gap-1.5 text-sm font-semibold">
					<Sparkles class="h-3.5 w-3.5 text-primary" aria-hidden="true" />
					DimSum · this canvas
				</h2>
				<div class="flex items-center gap-1.5">
					<button
						type="button"
						onclick={onClearChat}
						disabled={messages.length === 0}
						class="rounded border border-border px-2 py-1 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent hover:text-accent-foreground disabled:cursor-not-allowed disabled:opacity-50"
						title="Wipe the conversation history"
						data-testid="canvas-ai-chat-clear"
					>
						Clear chat
					</button>
					<button
						type="button"
						onclick={() => (chatOpen = false)}
						class="rounded p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
						aria-label="Close chat"
					>
						<X class="h-4 w-4" aria-hidden="true" />
					</button>
				</div>
			</header>

			<div
				bind:this={aiChatLogEl}
				class="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-4"
				data-testid="canvas-ai-chat-log"
			>
				{#if messages.length === 0 && !drafting}
					<div class="flex h-full flex-col items-center justify-center gap-2 text-center">
						<Sparkles class="h-6 w-6 text-primary" aria-hidden="true" />
						<p class="text-sm font-medium">Start a conversation about this canvas</p>
						<p class="max-w-[42ch] text-xs text-muted-foreground">
							Ask questions (“what cubes are here?”) or request changes (“add a join from
							orders.customer_id to customers.id”). History persists per connection.
						</p>
					</div>
				{:else}
					{#each messages as m, i (i)}
						{@const blocks = (
							typeof m.content === 'string' ? [{ type: 'text', text: m.content }] : m.content
						) as AnthropicBlock[]}
						{@const isToolResultTurn =
							m.role === 'user' &&
							Array.isArray(m.content) &&
							(m.content as AnthropicBlock[]).every((b) => b.type === 'tool_result')}
						{#if isToolResultTurn}
							<!-- Tool results — one small pill per result so
							     the conversation shows the reads/mutations
							     happening without shouting the JSON output. -->
							<div class="flex flex-col gap-1" data-testid="canvas-ai-chat-toolresults">
								{#each blocks as b, bi (bi)}
									{#if b.type === 'tool_result'}
										<div
											class="self-start rounded border border-border bg-muted/30 px-2 py-1 text-[10px] text-muted-foreground"
										>
											<span class={b.is_error ? 'text-destructive' : 'text-success'}>
												{b.is_error ? '✗' : '✓'}
											</span>
											<span class="ml-1 font-mono">tool result</span>
										</div>
									{/if}
								{/each}
							</div>
						{:else}
							<div
								class="flex gap-2 {m.role === 'user' ? 'justify-end' : 'justify-start'}"
								data-testid="canvas-ai-chat-message"
								data-role={m.role}
							>
								<div
									class="max-w-[80%] rounded-lg px-3 py-2 text-xs leading-relaxed {m.role === 'user'
										? 'bg-primary text-primary-foreground'
										: 'border border-border bg-muted/40 text-foreground'}"
								>
									{#if m.role === 'assistant'}
										<div
											class="mb-1 flex items-center gap-1 text-[9px] font-semibold tracking-wider text-muted-foreground uppercase"
										>
											<Sparkles class="h-2.5 w-2.5 text-primary" aria-hidden="true" />
											DimSum
										</div>
									{/if}
									{#each blocks as b, bi (bi)}
										{#if b.type === 'text' && b.text}
											<p class="whitespace-pre-wrap">{b.text}</p>
										{:else if b.type === 'tool_use'}
											<!-- Tool call pill — small mono label -->
											<div
												class="my-1 inline-flex items-center gap-1 rounded border border-primary/40 bg-primary/5 px-1.5 py-0.5 font-mono text-[10px] text-primary"
											>
												<Sparkles class="h-2.5 w-2.5" aria-hidden="true" />
												{b.name}
												{#if Object.keys(b.input ?? {}).length > 0}
													<span class="text-muted-foreground"
														>({Object.entries(b.input)
															.map(
																([k, v]) => `${k}=${typeof v === 'string' ? v : JSON.stringify(v)}`
															)
															.join(', ')})</span
													>
												{/if}
											</div>
										{/if}
									{/each}
								</div>
							</div>
						{/if}
					{/each}
					{#if drafting}
						<div class="flex justify-start gap-2">
							<div
								class="max-w-[80%] rounded-lg border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground"
							>
								<Loader2 class="mr-1 inline h-3 w-3 animate-spin" aria-hidden="true" />
								Working…
							</div>
						</div>
					{/if}
				{/if}
			</div>

			<footer class="shrink-0 border-t border-border p-3">
				<div class="flex items-end gap-2">
					<textarea
						bind:value={chatDraft}
						onkeydown={(e) => {
							if (e.key === 'Enter' && !e.shiftKey) {
								e.preventDefault();
								onSend(chatDraft);
							}
						}}
						placeholder="Reply to the AI — Enter to send, Shift+Enter for a new line."
						rows="2"
						maxlength="2000"
						disabled={drafting}
						class="min-h-0 flex-1 resize-none rounded border border-input bg-background px-2 py-1.5 text-xs leading-relaxed placeholder:text-muted-foreground focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none disabled:opacity-60"
						data-testid="canvas-ai-chat-input"
					></textarea>
					<button
						type="button"
						onclick={() => onSend(chatDraft)}
						disabled={drafting || !store.connectionId || !chatDraft.trim()}
						class="inline-flex h-8 shrink-0 items-center gap-1 rounded bg-primary px-3 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
						data-testid="canvas-ai-chat-send"
					>
						{#if drafting}
							<Loader2 class="h-3 w-3 animate-spin" aria-hidden="true" />
						{:else}
							<Sparkles class="h-3 w-3" aria-hidden="true" />
						{/if}
						Send
					</button>
				</div>
			</footer>
		</div>
	</div>
{/if}

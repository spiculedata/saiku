<script lang="ts">
	/*
	 * Notebook markdown cell (issue #1108). Same two-stage render + sanitise
	 * pipeline as TextTile.svelte (dashboard text tiles): renderTinyMarkdown
	 * escapes every source node before emitting its small set of structural
	 * tags, then DOMPurify strips anything that slipped through as
	 * defence-in-depth — a notebook cell is analyst-controllable (JCR write
	 * access) exactly like a dashboard text tile, same threat model.
	 */
	import DOMPurify from 'dompurify';
	import { renderTinyMarkdown } from '$lib/api/tinyMarkdown';
	import { i18n } from '$lib/stores/i18n.svelte';

	interface Props {
		markdown: string;
		readOnly?: boolean;
		onChange?: (markdown: string) => void;
	}

	let { markdown, readOnly = false, onChange }: Props = $props();

	// New cells start empty — editing, not previewing, is the useful default.
	let editing = $state(markdown.trim() === '' && !readOnly);

	const SANITISE_CONFIG = {
		FORBID_TAGS: ['style', 'embed', 'object', 'iframe', 'form']
	};

	let safeHtml = $derived(DOMPurify.sanitize(renderTinyMarkdown(markdown), SANITISE_CONFIG));

	function onInput(e: Event) {
		onChange?.((e.currentTarget as HTMLTextAreaElement).value);
	}
</script>

<div class="md-cell">
	{#if !readOnly}
		<div class="md-cell__toolbar">
			<button
				type="button"
				class="md-cell__toggle"
				class:md-cell__toggle--active={editing}
				onclick={() => (editing = true)}
			>
				{i18n.t('notebook.cell.edit', 'Edit')}
			</button>
			<button
				type="button"
				class="md-cell__toggle"
				class:md-cell__toggle--active={!editing}
				onclick={() => (editing = false)}
			>
				{i18n.t('notebook.cell.preview', 'Preview')}
			</button>
		</div>
	{/if}
	{#if editing && !readOnly}
		<textarea
			class="md-cell__editor"
			value={markdown}
			oninput={onInput}
			placeholder={i18n.t('notebook.cell.markdownPlaceholder', 'Write markdown…')}
			rows="4"></textarea>
	{:else if markdown.trim() === ''}
		<div class="md-cell__placeholder">{i18n.t('notebook.cell.emptyMarkdown', 'Empty cell')}</div>
	{:else}
		<!-- eslint-disable-next-line svelte/no-at-html-tags — sanitised via DOMPurify, see TextTile.svelte -->
		<div class="md-cell__preview">{@html safeHtml}</div>
	{/if}
</div>

<style>
	.md-cell {
		padding: 0.5rem 0.25rem;
	}
	.md-cell__toolbar {
		display: flex;
		gap: 4px;
		margin-bottom: 4px;
	}
	.md-cell__toggle {
		font-size: var(--fs-xs);
		padding: 2px 8px;
		border-radius: 999px;
		border: 1px solid hsl(var(--border));
		background: transparent;
		color: hsl(var(--fg-muted));
		cursor: pointer;
	}
	.md-cell__toggle--active {
		background: hsl(var(--bg-subtle));
		color: hsl(var(--fg));
	}
	.md-cell__editor {
		width: 100%;
		box-sizing: border-box;
		font-family: var(--font-mono, monospace);
		font-size: var(--fs-sm);
		padding: 0.5rem;
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-md, 6px);
		background: hsl(var(--bg));
		color: hsl(var(--fg));
		resize: vertical;
	}
	.md-cell__placeholder {
		color: hsl(var(--fg-muted));
		font-size: var(--fs-sm);
		font-style: italic;
		padding: 0.25rem;
	}
	.md-cell__preview {
		font-size: var(--fs-md);
		line-height: 1.5;
		color: hsl(var(--fg));
	}
	.md-cell__preview :global(h3),
	.md-cell__preview :global(h4),
	.md-cell__preview :global(h5),
	.md-cell__preview :global(h6) {
		margin: 0.5em 0 0.25em;
		font-weight: var(--weight-semibold);
	}
	.md-cell__preview :global(p) {
		margin: 0.35em 0;
	}
	.md-cell__preview :global(ul) {
		margin: 0.35em 0;
		padding-left: 1.25rem;
	}
	.md-cell__preview :global(code) {
		background: hsl(var(--bg-subtle));
		padding: 0.0625em 0.25em;
		border-radius: 3px;
		font-size: 0.85em;
	}
	.md-cell__preview :global(a) {
		color: hsl(var(--primary));
		text-decoration: underline;
	}
</style>

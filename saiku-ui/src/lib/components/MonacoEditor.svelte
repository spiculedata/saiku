<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { browser } from '$app/environment';
	import type * as Monaco from 'monaco-editor';
	import { theme } from '$lib/stores/theme.svelte';

	interface Props {
		value: string;
		language?: string;
		readOnly?: boolean;
		minHeight?: string;
		onChange?: (value: string) => void;
	}

	let {
		value,
		language = 'mdx',
		readOnly = false,
		minHeight = '260px',
		onChange
	}: Props = $props();

	let host: HTMLDivElement | null = null;
	let editor: Monaco.editor.IStandaloneCodeEditor | null = null;
	let monacoMod: typeof Monaco | null = null;
	let suppressChange = false;

	function resolveTheme(): string {
		if (!browser) return 'vs-dark';
		const explicit = document.documentElement.getAttribute('data-theme');
		if (explicit === 'light') return 'vs';
		if (explicit === 'dark') return 'vs-dark';
		return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'vs-dark' : 'vs';
	}

	onMount(async () => {
		if (!browser || !host) return;
		const { configureMonacoWorkers } = await import('$lib/monaco/worker-env');
		configureMonacoWorkers();
		monacoMod = await import('monaco-editor');
		const { registerMdxLanguage } = await import('$lib/monaco/mdx-lang');
		registerMdxLanguage();

		editor = monacoMod.editor.create(host, {
			value,
			language,
			readOnly,
			automaticLayout: true,
			fontSize: 13,
			fontFamily:
				'ui-monospace, "SF Mono", Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
			minimap: { enabled: false },
			scrollbar: { vertical: 'auto', horizontal: 'auto' },
			wordWrap: 'on',
			theme: resolveTheme()
		});

		editor.onDidChangeModelContent(() => {
			if (suppressChange || !editor) return;
			onChange?.(editor.getValue());
		});
	});

	$effect(() => {
		// Sync external value changes.
		if (editor && editor.getValue() !== value) {
			suppressChange = true;
			editor.setValue(value);
			suppressChange = false;
		}
	});

	$effect(() => {
		// Track theme swaps live.
		const t = theme.theme;
		void t; // mark dependency
		if (editor && monacoMod) {
			monacoMod.editor.setTheme(resolveTheme());
		}
	});

	onDestroy(() => {
		editor?.dispose();
		editor = null;
	});
</script>

<div
	class="w-full overflow-hidden rounded-sm border border-border"
	style="min-height: {minHeight}"
	bind:this={host}
></div>

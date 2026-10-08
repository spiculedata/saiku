<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { browser } from '$app/environment';
	import type * as Monaco from 'monaco-editor';
	import { theme } from '$lib/stores/theme.svelte';

	interface Props {
		value: string;
		/** A custom language id (e.g. `mondrian-xml`) must already be registered — via
		 *  `monaco.languages.register` + its tokenizer/completion provider — before this
		 *  component mounts, since registration happens on the `monaco-editor` module this
		 *  component dynamically imports itself. The caller does its own dynamic import of
		 *  the registration module in ITS OWN `onMount` and gates rendering this component
		 *  on that having finished; a `registerLanguage` prop called from inside this
		 *  component's `onMount` cannot do this safely, because Svelte mounts a child's
		 *  `onMount` before its parent's, so the parent would not yet have run it. */
		language?: string;
		readOnly?: boolean;
		minHeight?: string;
		onChange?: (value: string) => void;
		/**
		 * Opt in to a dedicated, long-lived Monaco model keyed by this URI string instead of
		 * the single shared model this component otherwise mounts `value` into directly.
		 * Switching `path` (e.g. a file-tree selection) swaps to that path's own model —
		 * created fresh from `value` the first time a path is seen, reused (with its own
		 * undo history and any in-progress edits intact) on every path after that. Because
		 * of that reuse, later `value` changes for an already-open path are NOT re-applied —
		 * `onChange` is the source of truth for a path's live content once its model exists.
		 * Leave unset for the single-buffer behavior the query-editor modals use.
		 */
		path?: string;
		/** Diagnostics rendered as squiggles + surfaced to the Problems pane / hover, applied
		 *  to whichever model is currently attached (i.e. the model for the current `path`). */
		markers?: Monaco.editor.IMarkerData[];
	}

	let {
		value,
		language = 'mdx',
		readOnly = false,
		minHeight = '260px',
		onChange,
		path,
		markers
	}: Props = $props();

	let host: HTMLDivElement | null = null;
	let editor: Monaco.editor.IStandaloneCodeEditor | null = null;
	let monacoMod: typeof Monaco | null = null;
	let suppressChange = false;
	// `editor`/`monacoMod` are plain (non-rune) locals assigned inside the async `onMount`,
	// so an `$effect` reading them directly tracks nothing and may run its one-and-only pass
	// before they exist. `mounted` is the rune dependency that guarantees every effect below
	// re-runs at least once AFTER the editor is actually ready, however `path`/`markers` look
	// at mount time (e.g. the very first file opens with zero lint issues — `markers` never
	// changes again, so an effect keyed only on `markers` would otherwise never apply it).
	let mounted = $state(false);
	// Models this instance created in `path` mode, keyed by `path` — disposed on unmount.
	// Monaco keeps one global model registry keyed by URI, so re-selecting a previously
	// opened `path` reuses its entry here rather than creating a duplicate (which Monaco
	// would reject with "model already exists").
	const ownedModels = new Map<string, Monaco.editor.ITextModel>();
	const MARKER_OWNER = 'model-ide-validate';

	function resolveTheme(): string {
		if (!browser) return 'vs-dark';
		const explicit = document.documentElement.getAttribute('data-theme');
		if (explicit === 'light') return 'vs';
		if (explicit === 'dark') return 'vs-dark';
		return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'vs-dark' : 'vs';
	}

	/** Get-or-create the model for `path` (path mode only). */
	function ensureModel(p: string, initialValue: string, lang: string): Monaco.editor.ITextModel {
		const mod = monacoMod!;
		const uri = mod.Uri.parse(p);
		const existing = mod.editor.getModel(uri);
		if (existing) return existing;
		const created = mod.editor.createModel(initialValue, lang, uri);
		ownedModels.set(p, created);
		return created;
	}

	onMount(async () => {
		if (!browser || !host) return;
		const { configureMonacoWorkers } = await import('$lib/monaco/worker-env');
		configureMonacoWorkers();
		monacoMod = await import('monaco-editor');
		const { registerMdxLanguage } = await import('$lib/monaco/mdx-lang');
		registerMdxLanguage();

		editor = monacoMod.editor.create(host, {
			...(path ? { model: ensureModel(path, value, language) } : { value, language }),
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
		mounted = true;
	});

	$effect(() => {
		// Path mode: swap to `path`'s own model instead of mutating the shared one.
		if (!mounted || !editor || !monacoMod || !path) return;
		const current = editor.getModel();
		const target = ensureModel(path, value, language);
		if (current !== target) {
			editor.setModel(target);
		}
	});

	$effect(() => {
		// Single-buffer mode: sync external value changes into the one shared model.
		if (mounted && editor && !path && editor.getValue() !== value) {
			suppressChange = true;
			editor.setValue(value);
			suppressChange = false;
		}
	});

	$effect(() => {
		void path; // re-apply against the newly attached model on every file switch too
		if (!mounted || !editor || !monacoMod) return;
		const model = editor.getModel();
		if (!model) return;
		monacoMod.editor.setModelMarkers(model, MARKER_OWNER, markers ?? []);
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
		for (const model of ownedModels.values()) model.dispose();
		ownedModels.clear();
	});
</script>

<div
	class="w-full overflow-hidden rounded-sm border border-border"
	style="min-height: {minHeight}"
	bind:this={host}
></div>

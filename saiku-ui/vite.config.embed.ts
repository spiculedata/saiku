/// <reference types="node" />
/*
 * Standalone Vite config for the embed Web Component bundles
 * (<saiku-embed>, <saiku-chart>, <saiku-dashboard> — issue #1103 split
 * the latter two off the original all-in-one element).
 *
 * This is SEPARATE from vite.config.ts (the SvelteKit app) because:
 *   - the SvelteKit plugin doesn't trivially do library mode;
 *   - we want one self-contained file PER TAG with everything inlined,
 *     no chunk splitting, no manifest.json sidecar — a host page that
 *     only wants <saiku-chart> shouldn't have to ship the dashboard /
 *     app / AI-ask code that <saiku-embed> also bundles;
 *   - the customElement compile flag is set on @sveltejs/vite-plugin-svelte
 *     directly rather than going through the SvelteKit adapter.
 *
 * Vite's lib mode can't emit multiple IIFE globals from one `entry`
 * object (each IIFE needs its own `name`), so scripts/build-embed.mjs
 * invokes this config once per tag, selecting the entry via the
 * EMBED_ENTRY env var (set on the child-process env, not the shell, so
 * it's Windows-safe too). `npm run build:embed` runs that orchestrator.
 *
 * Output: dist/<tag>.js — alongside the SvelteKit app's dist/, so the
 * saiku-webapp pom's <resource> rule that copies dist/ to /ui/
 * automatically picks up every bundle. The host page references
 * https://<your-saiku>/ui/<tag>.js.
 *
 * `build:embed` runs AFTER `build` so the SvelteKit dist/ output is
 * already in place; this build's emptyOutDir: false keeps that intact.
 */
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';

/** One entry per embed custom element. Keep in sync with
 *  scripts/build-embed.mjs (which drives the multi-entry build) and
 *  embed-npm/ (which stages the resulting files for npm). */
export const EMBED_ENTRIES: Record<string, { source: string; globalName: string }> = {
	'saiku-embed': { source: 'src/embed/saiku-embed.ts', globalName: 'SaikuEmbed' },
	'saiku-chart': { source: 'src/embed/saiku-chart.ts', globalName: 'SaikuChart' },
	'saiku-dashboard': { source: 'src/embed/saiku-dashboard.ts', globalName: 'SaikuDashboard' }
};

const entryKey = process.env.EMBED_ENTRY ?? 'saiku-embed';
const entry = EMBED_ENTRIES[entryKey];
if (!entry) {
	throw new Error(
		`vite.config.embed.ts: unknown EMBED_ENTRY "${entryKey}" — expected one of ${Object.keys(EMBED_ENTRIES).join(', ')}`
	);
}

export default defineConfig({
	// Statically replace `process.env.NODE_ENV` at build time — ECharts (a
	// dependency of EmbedChart) uses it to gate development-only assertion
	// warnings. Without this replacement the bundle references `process` at
	// runtime and any host page without a Node-polyfill in scope throws
	// `ReferenceError: process is not defined` at first render.
	define: {
		'process.env.NODE_ENV': JSON.stringify('production')
	},
	plugins: [
		svelte({
			// Compile every .svelte under src/embed/ as a Web Component. The
			// tag name is declared inside each Saiku*.svelte entry via
			// <svelte:options customElement={…}>. Child components
			// (EmbedTable.svelte) get compiled as ordinary internals —
			// Svelte 5 only emits a custom element for components that
			// explicitly opt in.
			compilerOptions: { customElement: true }
		})
	],
	build: {
		// Library mode: single bundle, no html shell, no manifest.
		lib: {
			entry: entry.source,
			name: entry.globalName,
			formats: ['iife'],
			fileName: () => `${entryKey}.js`
		},
		// Land alongside the SvelteKit dist so the existing saiku-webapp
		// <resource> rule (copies dist/ to ui/) picks the bundle up for
		// free. emptyOutDir: false is critical — we'd otherwise wipe the
		// SvelteKit output (or a sibling embed bundle) that ran just
		// before us.
		outDir: 'dist',
		emptyOutDir: false,
		sourcemap: true,
		target: 'es2020',
		minify: true,
		rollupOptions: {
			// Inline EVERYTHING. The bundle is the unit of distribution; host
			// pages shouldn't have to know our import graph.
			external: []
		}
	}
});

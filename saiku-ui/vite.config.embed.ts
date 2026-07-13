/// <reference types="node" />
/*
 * Standalone Vite config for the <saiku-embed/> Web Component bundle.
 *
 * This is SEPARATE from vite.config.ts (the SvelteKit app) because:
 *   - the SvelteKit plugin doesn't trivially do library mode;
 *   - we want a single self-contained file with everything inlined,
 *     no chunk splitting, no manifest.json sidecar;
 *   - the customElement compile flag is set on @sveltejs/vite-plugin-svelte
 *     directly rather than going through the SvelteKit adapter.
 *
 * Output: dist/saiku-embed.js — alongside the SvelteKit app's dist/, so
 * the saiku-webapp pom's <resource> rule that copies dist/ to /ui/
 * automatically picks it up. The host page references
 * https://<your-saiku>/ui/saiku-embed.js.
 *
 * `build:embed` runs AFTER `build` so the SvelteKit dist/ output is
 * already in place; this build's emptyOutDir: false keeps that intact.
 */
import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vite";

export default defineConfig({
  // Statically replace `process.env.NODE_ENV` at build time — ECharts (a
  // dependency of EmbedChart) uses it to gate development-only assertion
  // warnings. Without this replacement the bundle references `process` at
  // runtime and any host page without a Node-polyfill in scope throws
  // `ReferenceError: process is not defined` at first render.
  define: {
    "process.env.NODE_ENV": JSON.stringify("production"),
  },
  plugins: [
    svelte({
      // Compile every .svelte under src/embed/ as a Web Component. The
      // tag name is declared inside SaikuEmbed.svelte via <svelte:options
      // customElement={…}>. Child components (EmbedTable.svelte) get
      // compiled as ordinary internals — Svelte 5 only emits a custom
      // element for components that explicitly opt in.
      compilerOptions: { customElement: true },
    }),
  ],
  build: {
    // Library mode: single bundle, no html shell, no manifest.
    lib: {
      entry: "src/embed/saiku-embed.ts",
      name: "SaikuEmbed",
      formats: ["iife"],
      fileName: () => "saiku-embed.js",
    },
    // Land alongside the SvelteKit dist so the existing saiku-webapp
    // <resource> rule (copies dist/ to ui/) picks the bundle up for
    // free. emptyOutDir: false is critical — we'd otherwise wipe the
    // SvelteKit output that ran just before us.
    outDir: "dist",
    emptyOutDir: false,
    sourcemap: true,
    target: "es2020",
    minify: true,
    rollupOptions: {
      // Inline EVERYTHING. The bundle is the unit of distribution; host
      // pages shouldn't have to know our import graph.
      external: [],
    },
  },
});

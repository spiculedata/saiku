let configured = false;

export function configureMonacoWorkers(): void {
  if (configured) return;
  configured = true;
  // For MDX/SQL text editing we only need the base editor worker; we don't
  // load the JSON / CSS / HTML / TS language services.
  (self as unknown as { MonacoEnvironment?: unknown }).MonacoEnvironment = {
    getWorker(): Worker {
      // Framework-agnostic Vite worker pattern. We deliberately avoid the
      // `monaco-editor/...?worker` import: the `?worker` suffix is a Vite
      // *client*-build feature whose plugin never runs in the SvelteKit
      // SSR/prerender bundle, so any `?worker` specifier Rollup traverses there
      // (static or dynamic) fails to resolve and breaks the build (saiku#1550).
      // `new Worker(new URL(...), { type: "module" })` is understood by Vite in
      // both bundles and constructs the Worker synchronously, as Monaco
      // requires. The path is relative rather than a bare `monaco-editor/...`
      // specifier because Vite's worker-URL resolver does not run bare package
      // specifiers through node resolution here.
      return new Worker(
        new URL(
          "../../../node_modules/monaco-editor/esm/vs/editor/editor.worker.js",
          import.meta.url,
        ),
        { type: "module" },
      );
    },
  };
}

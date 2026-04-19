import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";

let configured = false;

export function configureMonacoWorkers(): void {
  if (configured) return;
  configured = true;
  // For MDX/SQL text editing we only need the base editor worker; we don't
  // load the JSON / CSS / HTML / TS language services.
  (self as unknown as { MonacoEnvironment?: unknown }).MonacoEnvironment = {
    getWorker(): Worker {
      return new EditorWorker();
    },
  };
}

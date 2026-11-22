/*
 * Monaco wiring for Ossie YAML completion (saiku#1428 Model IDE) — the translation layer
 * between `monaco-editor`'s model/position API and the pure decision logic in
 * `ossie-yaml-completions.ts` (which has no monaco import and is unit tested directly).
 *
 * Tokenization reuses Monaco's bundled `yaml` basic language — it is already part of the
 * `monaco-editor` main import `MonacoEditor.svelte` loads, so nothing to register. Only the
 * completion provider is wired here, registered against the `yaml` language id: this is the
 * app's only YAML editor (the Mondrian side uses its own `mondrian-xml` id), so a global
 * override on `yaml` is safe and a hand-rolled duplicate Monarch grammar is avoided.
 */
import * as monaco from 'monaco-editor';
import type * as Monaco from 'monaco-editor';
import { completeOssieYaml, type OssieCatalogue } from './ossie-yaml-completions';

let registered = false;

/**
 * Register the completion provider once per editor lifetime. `getCatalogue` is a closure the
 * provider calls on every completion request, so the live model fetched for the file being
 * edited is picked up without re-registering.
 */
export function registerOssieYamlLanguage(getCatalogue: () => OssieCatalogue): void {
	if (registered) return;
	registered = true;

	monaco.languages.registerCompletionItemProvider('yaml', {
		triggerCharacters: [':', ' '],
		provideCompletionItems(model, position) {
			const catalogue = getCatalogue();
			const word = model.getWordUntilPosition(position);
			const linePrefix = model.getValueInRange({
				startLineNumber: position.lineNumber,
				startColumn: 1,
				endLineNumber: position.lineNumber,
				endColumn: position.column
			});
			const lines = model.getValue().split('\n');
			const candidates = completeOssieYaml(lines, position.lineNumber - 1, linePrefix, catalogue);
			const range: Monaco.IRange = {
				startLineNumber: position.lineNumber,
				endLineNumber: position.lineNumber,
				startColumn: word.startColumn,
				endColumn: word.endColumn
			};
			return {
				suggestions: candidates.map((c) => ({
					label: c.label,
					kind:
						c.kind === 'key'
							? monaco.languages.CompletionItemKind.Field
							: monaco.languages.CompletionItemKind.Value,
					insertText: c.insertText,
					detail: c.detail,
					range
				}))
			};
		}
	});
}

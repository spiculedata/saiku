/*
 * Monaco language registration for Mondrian 4 schema XML (saiku#1428 Model IDE).
 *
 * Registered under its own id (`mondrian-xml`) rather than reusing Monaco's bundled `xml`
 * basic language: the completion provider below is registered against this id specifically,
 * so it never fires for an unrelated XML editor elsewhere in the app (there isn't one today,
 * but a language id is cheap insurance a global override on `xml` would not be).
 *
 * The tokenizer is a small hand-rolled Monarch grammar covering tags/attributes/strings/
 * comments/CDATA — enough for readable syntax highlighting, not a spec-complete XML grammar.
 *
 * All the completion DECISION logic lives in `mondrian-xml-completions.ts`, which has no
 * `monaco-editor` import and is unit tested directly. This file only translates Monaco's
 * model/position API into that module's plain-text inputs and maps its output onto
 * `monaco.languages.CompletionItem`.
 */
import * as monaco from 'monaco-editor';
import type { MondrianCatalogue } from './mondrian-catalogue';
import { computeCompletionCandidates, type CompletionCandidate } from './mondrian-xml-completions';

export const MONDRIAN_XML_LANGUAGE_ID = 'mondrian-xml';

let registered = false;

function registerTokenizer(): void {
	monaco.languages.register({ id: MONDRIAN_XML_LANGUAGE_ID, extensions: ['.xml'] });

	monaco.languages.setMonarchTokensProvider(MONDRIAN_XML_LANGUAGE_ID, {
		defaultToken: '',
		tokenPostfix: '.mondrian-xml',
		tokenizer: {
			root: [
				[/<!--/, 'comment', '@comment'],
				[/<!\[CDATA\[/, 'string.cdata', '@cdata'],
				[/<\?/, 'metatag', '@pi'],
				[/<!DOCTYPE/, 'metatag', '@doctype'],
				[/<\/[A-Za-z_][\w.-]*\s*>/, 'tag'],
				[/<[A-Za-z_][\w.-]*/, 'tag', '@tag']
			],
			tag: [
				[/\s+/, 'white'],
				[/[A-Za-z_][\w.-]*(?=\s*=)/, 'attribute.name'],
				[/=/, 'delimiter'],
				[/"([^"]*)"/, 'attribute.value'],
				[/'([^']*)'/, 'attribute.value'],
				[/\/?>/, 'tag', '@pop']
			],
			comment: [
				[/-->/, 'comment', '@pop'],
				[/[^-]+/, 'comment'],
				[/-/, 'comment']
			],
			cdata: [
				[/\]\]>/, 'string.cdata', '@pop'],
				[/[^\]]+/, 'string.cdata'],
				[/\]/, 'string.cdata']
			],
			pi: [
				[/\?>/, 'metatag', '@pop'],
				[/[^?]+/, 'metatag'],
				[/\?/, 'metatag']
			],
			doctype: [
				[/>/, 'metatag', '@pop'],
				[/[^>]+/, 'metatag']
			]
		}
	});

	monaco.languages.setLanguageConfiguration(MONDRIAN_XML_LANGUAGE_ID, {
		comments: { blockComment: ['<!--', '-->'] },
		brackets: [['<', '>']],
		autoClosingPairs: [
			{ open: '"', close: '"' },
			{ open: "'", close: "'" }
		]
	});
}

function toMonacoKind(kind: CompletionCandidate['kind']): monaco.languages.CompletionItemKind {
	switch (kind) {
		case 'element':
			return monaco.languages.CompletionItemKind.Class;
		case 'attribute':
			return monaco.languages.CompletionItemKind.Property;
		case 'value':
			return monaco.languages.CompletionItemKind.Value;
	}
}

/**
 * Register the schema-aware completion provider. Takes a `getCatalogue` callback rather
 * than a snapshot so the provider always sees whatever the IDE page most recently fetched
 * from `/ai/cubes` — completion runs long after registration, on every keystroke.
 */
function registerCompletion(getCatalogue: () => MondrianCatalogue): void {
	monaco.languages.registerCompletionItemProvider(MONDRIAN_XML_LANGUAGE_ID, {
		triggerCharacters: ['<', ' ', '"'],
		provideCompletionItems(model, position) {
			const lineToCursor = model.getValueInRange({
				startLineNumber: position.lineNumber,
				startColumn: 1,
				endLineNumber: position.lineNumber,
				endColumn: position.column
			});
			const wordInfo = model.getWordUntilPosition(position);
			const range = new monaco.Range(
				position.lineNumber,
				wordInfo.startColumn,
				position.lineNumber,
				wordInfo.endColumn
			);

			const candidates = computeCompletionCandidates({
				lineToCursor,
				fullText: model.getValue(),
				offset: model.getOffsetAt(position),
				catalogue: getCatalogue()
			});

			return {
				suggestions: candidates.map((c) => ({
					label: c.label,
					kind: toMonacoKind(c.kind),
					insertText: c.insertText,
					insertTextRules:
						c.kind === 'attribute'
							? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet
							: undefined,
					detail: c.detail,
					range
				}))
			};
		}
	});
}

export function registerMondrianXmlLanguage(getCatalogue: () => MondrianCatalogue): void {
	if (registered) return;
	registered = true;
	registerTokenizer();
	registerCompletion(getCatalogue);
}

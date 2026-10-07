import * as monaco from 'monaco-editor';
import {
	buildMdxCompletions,
	EMPTY_MDX_COMPLETION_CONTEXT,
	type MdxCompletionContext,
	type MdxCompletionKind
} from './mdxCompletion';

let registered = false;

/**
 * Schema backing the cube-grounded completion provider (saiku#1106 phase 2).
 * Registration happens once (guarded below), but the provider reads this
 * mutable slot on every keystroke, so whichever surface last called
 * {@link setMdxCompletionContext} — the workbench route or the toolbar's MDX
 * modal — drives suggestions for every open MDX editor. There's only ever
 * one MDX editor visible at a time today, so a shared slot is simpler than
 * threading per-editor context through Monaco's global language registry.
 */
let completionContext: MdxCompletionContext = EMPTY_MDX_COMPLETION_CONTEXT;

export function setMdxCompletionContext(ctx: MdxCompletionContext): void {
	completionContext = ctx;
}

export function clearMdxCompletionContext(): void {
	completionContext = EMPTY_MDX_COMPLETION_CONTEXT;
}

const COMPLETION_ITEM_KIND: Record<MdxCompletionKind, monaco.languages.CompletionItemKind> = {
	measure: monaco.languages.CompletionItemKind.Field,
	dimension: monaco.languages.CompletionItemKind.Class,
	hierarchy: monaco.languages.CompletionItemKind.Struct,
	level: monaco.languages.CompletionItemKind.Property
};

const MDX_KEYWORDS = [
	'WITH',
	'SELECT',
	'FROM',
	'WHERE',
	'ON',
	'COLUMNS',
	'ROWS',
	'PAGES',
	'CHAPTERS',
	'SECTIONS',
	'MEMBER',
	'SET',
	'AS',
	'AXIS',
	'NON',
	'EMPTY',
	'PROPERTIES',
	'CELL',
	'DIMENSION',
	'HIERARCHY',
	'LEVEL',
	'MEASURES',
	'ORDER',
	'DESC',
	'ASC',
	'BDESC',
	'BASC',
	'FILTER',
	'CROSSJOIN',
	'UNION',
	'INTERSECT',
	'EXCEPT',
	'TOPCOUNT',
	'BOTTOMCOUNT',
	'HEAD',
	'TAIL',
	'DESCENDANTS',
	'ANCESTOR',
	'PARENT',
	'CHILDREN',
	'ALLMEMBERS',
	'CURRENTMEMBER',
	'DEFAULTMEMBER',
	'HIERARCHIZE',
	'SUM',
	'COUNT',
	'AVG',
	'MIN',
	'MAX'
];

export function registerMdxLanguage(): void {
	if (registered) return;
	registered = true;

	monaco.languages.register({ id: 'mdx' });

	monaco.languages.setMonarchTokensProvider('mdx', {
		ignoreCase: true,
		defaultToken: '',
		tokenPostfix: '.mdx',
		keywords: MDX_KEYWORDS,
		operators: ['=', '>', '<', '<=', '>=', '<>', '+', '-', '*', '/', '&'],
		tokenizer: {
			root: [
				[/--.*$/, 'comment'],
				[/\/\/.*$/, 'comment'],
				[/\/\*/, 'comment', '@comment'],
				[/'([^'\\]|\\.)*'/, 'string'],
				[/"([^"\\]|\\.)*"/, 'string'],
				[/\[[^\]]*\]/, 'type.identifier'],
				[
					/[a-zA-Z_][\w]*/,
					{
						cases: {
							'@keywords': { token: 'keyword' },
							'@default': 'identifier'
						}
					}
				],
				[/[+\-*/=<>!&|]/, 'operator'],
				[/\d+(\.\d+)?/, 'number'],
				[/[{}()[\]]/, 'delimiter.bracket'],
				[/[;,.]/, 'delimiter'],
				[/\s+/, 'white']
			],
			comment: [
				[/[^/*]+/, 'comment'],
				[/\*\//, 'comment', '@pop'],
				[/[/*]/, 'comment']
			]
		}
	});

	monaco.languages.setLanguageConfiguration('mdx', {
		comments: { lineComment: '--', blockComment: ['/*', '*/'] },
		brackets: [
			['{', '}'],
			['[', ']'],
			['(', ')']
		],
		autoClosingPairs: [
			{ open: '{', close: '}' },
			{ open: '[', close: ']' },
			{ open: '(', close: ')' },
			{ open: '"', close: '"' },
			{ open: "'", close: "'" }
		]
	});

	// saiku#1106 phase 2: cube-grounded member completion. Reads whatever
	// schema setMdxCompletionContext() last stashed rather than capturing it
	// at registration time, since registerMdxLanguage() only runs once per
	// page load (guarded above) but the active cube can change afterwards.
	monaco.languages.registerCompletionItemProvider('mdx', {
		triggerCharacters: ['[', '.'],
		provideCompletionItems(model, position) {
			const textBeforeCursor = model.getValueInRange({
				startLineNumber: 1,
				startColumn: 1,
				endLineNumber: position.lineNumber,
				endColumn: position.column
			});
			const { replacePrefixLength, candidates } = buildMdxCompletions(
				textBeforeCursor,
				completionContext
			);
			if (candidates.length === 0) return { suggestions: [] };
			const range = new monaco.Range(
				position.lineNumber,
				position.column - replacePrefixLength,
				position.lineNumber,
				position.column
			);
			return {
				suggestions: candidates.map((c) => ({
					label: c.label,
					detail: c.detail,
					kind: COMPLETION_ITEM_KIND[c.kind],
					insertText: c.insertText,
					range
				}))
			};
		}
	});
}

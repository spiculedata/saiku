import * as monaco from 'monaco-editor';

let registered = false;

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
}

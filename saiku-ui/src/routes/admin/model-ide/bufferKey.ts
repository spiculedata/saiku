/**
 * Single source of truth for the editor buffer/baseline key of an open Model IDE file.
 *
 * Mondrian schemas use the historical `xml:` prefix (that is what selectSchema,
 * startNewSchema and saveMondrian write under); Ossie models use `ossie:`.
 * Every buffer read, write, dirty check and save must derive its key here — a key
 * spelled differently from the write site silently shows an empty editor and drops
 * the user's edits on save (see review finding on PR #2490).
 */
export type MondrianSelection = { kind: 'mondrian'; schema: { name: string } };
export type OssieSelection = { kind: 'ossie'; datasource: { name: string } };
export type IdeSelection = MondrianSelection | OssieSelection;

export function bufferKeyFor(sel: IdeSelection): string {
	return sel.kind === 'mondrian' ? `xml:${sel.schema.name}` : `ossie:${sel.datasource.name}`;
}

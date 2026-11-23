import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const resources = join(root, 'saiku-core/saiku-web/src/main/resources/org/saiku/web/export');
const context = vm.createContext({});
for (const name of ['underscore.js', 'SaikuRenderer.js', 'SaikuTableRenderer.js']) {
  vm.runInContext(readFileSync(join(resources, name), 'utf8'), context, { filename: name });
}

context.result = [
  [{ type: 'COLUMN_HEADER', value: 'Year', properties: {} }, { type: 'COLUMN_HEADER', value: 'Sales', properties: {} }],
  [{ type: 'ROW_HEADER', value: '1997', properties: {} }, { type: 'DATA_CELL', value: '<img src="http://127.0.0.1/private">', properties: {} }],
];
const html = vm.runInContext('new SaikuTableRenderer().render({cellset: result, topOffset: 1, leftOffset: 1, rowTotalsLists: null, colTotalsLists: null}, {wrapContent: false})', context);
assert.match(html, /&lt;img src=&quot;http:\/\/127\.0\.0\.1\/private&quot;&gt;/);
assert.doesNotMatch(html, /<img\b/i);

const stylesheet = readFileSync(join(resources, 'xhtml2fo.xsl'), 'utf8');
assert.doesNotMatch(stylesheet, /<fo:external-graphic\b/);
assert.match(stylesheet, /<xsl:template match="xhtml:img\|img\|xhtml:input\[@type='image'\]\|input\[@type='image'\]"\s*\/>/);
assert.match(stylesheet, /<xsl:template match="xhtml:object\[starts-with\(@type,'image\/'\)\]\|object\[starts-with\(@type,'image\/'\)\]"\s*\/>/);

process.stdout.write('query-result HTML escapes image markup and the PDF stylesheet omits external graphics\n');

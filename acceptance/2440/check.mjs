// Acceptance spec for spiculedata/saiku#2440: a dashboard/app plugin tile must render under the
// enforced shell CSP (#1917). An <iframe srcdoc> inherits the shell's `script-src 'self' 'sha256-...'`
// and blocks the plugin, so the frame is now the network-loaded same-origin bootstrap
// /ui/plugin-frame.html, served with its own sandboxed policy. The browser proof is
// saiku-ui/e2e/plugin-tile-csp.spec.ts (the ui CI job); this check pins the contract and, when a
// launcher is configured (ACCEPTANCE_BASE_URL), the live response headers.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (path) => {
  try {
    return readFileSync(join(root, path), 'utf8');
  } catch {
    return '';
  }
};

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

// C1: the in-app tile loads the bootstrap, not an srcdoc.
const tile = read('saiku-ui/src/lib/views/dashboard/tiles/custom/PluginTile.svelte');
const iframe = tile.match(/<iframe[\s\S]*?>/)?.[0] ?? '';
claim('C1', 'PluginTile.svelte renders an iframe', iframe !== '');
claim('C1', 'the plugin iframe does not use srcdoc', iframe !== '' && !/srcdoc/i.test(iframe));
claim('C1', 'the plugin iframe keeps sandbox="allow-scripts" and nothing else', /sandbox="allow-scripts"/.test(iframe));
claim('C1', 'PluginTile.svelte loads plugin-frame.html', /PLUGIN_FRAME_PATH|plugin-frame\.html/.test(tile));

// C2: the bootstrap.
const boot = read('saiku-ui/static/plugin-frame.html');
claim('C2', 'saiku-ui/static/plugin-frame.html exists', boot !== '');
claim('C2', 'the bootstrap refuses to run unframed', /parent\w*\s*===\s*window\b/.test(boot));
claim('C2', 'the bootstrap only accepts messages from its parent window', /\.source\s*!==\s*\w*parent/i.test(boot));
claim('C2', 'the bootstrap checks the sender origin', /\.origin\s*!==/.test(boot));
claim('C2', 'the bootstrap never fetches', boot !== '' && !/fetch\(|XMLHttpRequest|import\(/.test(boot));

// C3: the frame's own policy.
const checkPolicy = (where, csp) => {
  const dirs = new Map(
    csp
      .split(';')
      .map((d) => d.trim())
      .filter(Boolean)
      .map((d) => {
        const [name, ...rest] = d.split(/\s+/);
        return [name.toLowerCase(), rest.join(' ')];
      })
  );
  claim('C3', `${where}: sandbox allow-scripts`, dirs.get('sandbox') === 'allow-scripts');
  claim('C3', `${where}: no allow-same-origin`, !/allow-same-origin/.test(csp));
  claim('C3', `${where}: default-src 'none'`, dirs.get('default-src') === "'none'");
  claim('C3', `${where}: script-src 'unsafe-inline' only`, dirs.get('script-src') === "'unsafe-inline'");
  claim('C3', `${where}: connect-src 'none'`, dirs.get('connect-src') === "'none'");
  claim('C3', `${where}: form-action 'none'`, dirs.get('form-action') === "'none'");
  claim('C3', `${where}: base-uri 'none'`, dirs.get('base-uri') === "'none'");
  claim('C3', `${where}: frame-ancestors present`, dirs.has('frame-ancestors'));
};

const base = (process.env.ACCEPTANCE_BASE_URL || process.env.SAIKU_BASE_URL || '').replace(/\/+$/, '');
if (base) {
  let res = null;
  try {
    res = await fetch(`${base}/ui/plugin-frame.html`, { redirect: 'manual' });
  } catch (e) {
    failures.push(`C3: GET ${base}/ui/plugin-frame.html failed: ${e.message}`);
  }
  if (res) {
    claim('C3', `GET /ui/plugin-frame.html is 200 (got ${res.status})`, res.status === 200);
    const body = await res.text();
    claim('C3', 'GET /ui/plugin-frame.html serves the bootstrap, not the SPA shell', /saiku-plugin-load/.test(body));
    checkPolicy('live header', res.headers.get('content-security-policy') ?? '');
    claim('C3', 'live: no report-only shell policy on the frame', !res.headers.get('content-security-policy-report-only'));
  }
  // The container decodes the path before it picks the file, so every spelling that serves the
  // bootstrap must carry the frame policy too.
  for (const spelling of ['/ui/%70lugin-frame.html', '/ui/plugin%2Dframe.html', '/%75i/plugin-frame.html']) {
    let r = null;
    try {
      r = await fetch(`${base}${spelling}`, { redirect: 'manual' });
    } catch (e) {
      failures.push(`C3: GET ${spelling} failed: ${e.message}`);
    }
    if (r && r.status === 200 && /saiku-plugin-load/.test(await r.text())) {
      checkPolicy(`live header for ${spelling}`, r.headers.get('content-security-policy') ?? '');
    }
  }
} else {
  const filter = read('saiku-core/saiku-web/src/main/java/org/saiku/web/servlet/SecurityHeadersFilter.java');
  const constant = filter.match(/PLUGIN_FRAME_CSP\s*=\s*((?:"[^"]*"\s*\+?\s*)+);/)?.[1] ?? '';
  const csp = [...constant.matchAll(/"([^"]*)"/g)].map((m) => m[1]).join('') + '; frame-ancestors ' + "'self'";
  claim('C3', 'SecurityHeadersFilter declares PLUGIN_FRAME_CSP', constant !== '');
  claim('C3', 'SecurityHeadersFilter serves it for /ui/plugin-frame.html', /\/ui\/plugin-frame\.html/.test(filter));
  claim('C3', 'SecurityHeadersFilter matches the decoded path (RequestPaths.pathWithinApplication)', /isPluginFrame[\s\S]*?RequestPaths\.pathWithinApplication/.test(filter));
  if (constant) checkPolicy('SecurityHeadersFilter.PLUGIN_FRAME_CSP', csp);
}

// C4: the browser proof.
const e2e = read('saiku-ui/e2e/plugin-tile-csp.spec.ts');
claim('C4', 'saiku-ui/e2e/plugin-tile-csp.spec.ts exists', e2e !== '');
claim('C4', 'it mounts the records-bars plugin', /records-bars/.test(e2e));
claim('C4', 'it asserts zero CSP violations', /cspMessages[\s\S]*toEqual\(\[\]\)/.test(e2e));

if (failures.length) {
  for (const f of failures) process.stderr.write(f + '\n');
  process.exit(1);
}
process.stdout.write('plugin tiles load a sandboxed same-origin frame with its own CSP (saiku#2440)\n');

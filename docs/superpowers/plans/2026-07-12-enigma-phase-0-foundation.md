# Enigma — Phase 0 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A deployed, branded, runnable "Enigma by Benafide" SvelteKit app with a global entity search and an entity-profile view over live data — the foundation every later pillar builds on.

**Architecture:** Standalone SvelteKit 2 / Svelte 5 app at `enigma/` in the saiku repo (sibling to `saiku-ui`, not in the Maven reactor). The SvelteKit **server** proxies to two tailnet-internal backends so the browser never touches them directly: the **Benafide KYB/UBO API** (`/v1/*`) and **Saiku** (`/rest/saiku/api/ai/ossie/*`). Phase 0 ships the shell, design system, typed API clients, search, profile, and a deploy path to the demo VM.

**Tech Stack:** SvelteKit 2, Svelte 5 (runes), TypeScript strict, Vite, Vitest (unit), the Enigma design tokens (plain CSS, no Tailwind for Phase 0), `@sveltejs/adapter-node` for the server proxy.

**Design spec:** `docs/superpowers/specs/2026-07-12-enigma-by-benafide-design.md`

---

## File structure (created in this plan)

```
enigma/
  package.json                     # deps + scripts
  svelte.config.js                 # adapter-node
  vite.config.ts                   # vitest config
  .env.example                     # backend URLs (tailnet)
  src/
    app.html                       # shell doc (fonts, favicon)
    app.css                        # Enigma design tokens + base
    lib/
      server/
        config.ts                  # reads env: BENAFIDE_API, SAIKU_API, tokens
        benafide.ts                # server-side Benafide client (fetch + types)
        saiku.ts                   # server-side Saiku Ossie client
      types.ts                     # shared DTOs (Entity, EntityRisk, ...)
      format.ts                    # pure helpers (risk band, jurisdiction flag)
      components/
        TopBar.svelte              # logo + global search + pillar nav
        SearchBox.svelte           # entity search input + results
        ScoreChip.svelte           # risk/opacity chip
    routes/
      +layout.svelte               # shell (TopBar + <slot/>)
      +page.svelte                 # landing (hero + search)
      api/
        entities/+server.ts        # proxy: GET /api/entities?q= -> Benafide /v1/entities
        entities/[id]/+server.ts   # proxy: entity detail + risk
      e/[id]/+page.ts              # profile loader
      e/[id]/+page.svelte          # entity profile view
  tests/
    format.test.ts
    benafide.test.ts
    saiku.test.ts
```

---

### Task 1: Scaffold the Enigma SvelteKit app

**Files:**
- Create: `enigma/` (via scaffolder)
- Modify: `enigma/package.json`, `enigma/svelte.config.js`

- [ ] **Step 1: Scaffold with the SvelteKit minimal + TS template**

Run from the saiku repo root:
```bash
cd /Users/tombarber/Projects/saiku/saiku
npm create svelte@latest enigma -- --template minimal --types ts --no-add-ons 2>/dev/null || \
  npx sv create enigma --template minimal --types ts --no-install
cd enigma && npm install
```
If the interactive prompt blocks, answer: Skeleton project, TypeScript, no ESLint/Prettier/Playwright/Vitest yet (added explicitly below).

- [ ] **Step 2: Add adapter-node + vitest**

Run:
```bash
cd enigma
npm install -D @sveltejs/adapter-node vitest @types/node
```

- [ ] **Step 3: Point svelte.config.js at adapter-node**

Replace `enigma/svelte.config.js` with:
```js
import adapter from '@sveltejs/adapter-node';
import { vitePreprocess } from '@sveltejs/kit/vite';

/** @type {import('@sveltejs/kit').Config} */
export default {
  preprocess: vitePreprocess(),
  kit: { adapter: adapter() }
};
```

- [ ] **Step 4: Configure vitest in vite.config.ts**

Replace `enigma/vite.config.ts` with:
```ts
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [sveltekit()],
  test: { include: ['tests/**/*.test.ts'], environment: 'node' }
});
```

- [ ] **Step 5: Add scripts + verify dev server boots**

Ensure `enigma/package.json` scripts include:
```json
"scripts": { "dev": "vite dev", "build": "vite build", "preview": "vite preview", "check": "svelte-check --tsconfig ./tsconfig.json", "test": "vitest run" }
```
Run: `cd enigma && npm run dev -- --port 5199` → open `http://localhost:5199`.
Expected: default SvelteKit page renders. Ctrl-C to stop.

- [ ] **Step 6: Commit**
```bash
cd /Users/tombarber/Projects/saiku/saiku
git add enigma
git commit -m "feat(enigma): scaffold SvelteKit app (adapter-node + vitest)"
```

---

### Task 2: Enigma design tokens + base styles

**Files:**
- Create: `enigma/src/app.css`
- Modify: `enigma/src/app.html`

- [ ] **Step 1: Write the design tokens + base CSS**

Create `enigma/src/app.css` (tokens lifted verbatim from the approved mockups):
```css
:root{
  --bg:#08090c; --bg2:#0d0f14; --panel:#10131c; --panel2:#141826; --line:#1e2231; --line2:#2a3044;
  --fg:#e7e9f0; --muted:#8b90a3; --dim:#5a5f72;
  --amber:#f5b544; --cyan:#57d6e6; --red:#ff5d6c; --green:#5fe0a0; --violet:#9b8cff;
  --mono:'JetBrains Mono',ui-monospace,monospace;
  --disp:'Fraunces',Georgia,serif;
  --sans:'Inter Tight',system-ui,sans-serif;
}
*{box-sizing:border-box;margin:0;padding:0}
html,body{height:100%}
body{background:var(--bg);color:var(--fg);font-family:var(--sans);-webkit-font-smoothing:antialiased}
a{color:inherit;text-decoration:none}
.label{font-family:var(--mono);font-size:10px;letter-spacing:2px;text-transform:uppercase;color:var(--dim)}
.mono{font-family:var(--mono)}
.disp{font-family:var(--disp)}
.grid-bg{background-image:linear-gradient(rgba(255,255,255,.02) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.02) 1px,transparent 1px);background-size:34px 34px}
```

- [ ] **Step 2: Wire fonts + app.css + favicon into app.html**

Replace the `<head>` contents of `enigma/src/app.html` to include (keep `%sveltekit.head%` and body `%sveltekit.body%`):
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,400;9..144,600;9..144,900&family=JetBrains+Mono:wght@400;500;700&family=Inter+Tight:wght@400;500;600&display=swap" rel="stylesheet">
```
And import the CSS globally by adding to `enigma/src/routes/+layout.svelte` (created in Task 3): `import '../app.css';`

- [ ] **Step 3: Commit**
```bash
git add enigma/src/app.css enigma/src/app.html
git commit -m "feat(enigma): design tokens, fonts, base styles"
```

---

### Task 3: App shell (layout + TopBar)

**Files:**
- Create: `enigma/src/routes/+layout.svelte`, `enigma/src/lib/components/TopBar.svelte`

- [ ] **Step 1: Create the TopBar component**

Create `enigma/src/lib/components/TopBar.svelte`:
```svelte
<script lang="ts">
  const pillars = ['The Web','Reveal','The Deck','Ask','Signals','Borderlines','Cases'];
  let { active = '' }: { active?: string } = $props();
</script>

<header class="bar">
  <a class="logo" href="/">ENIGMA<span class="by">Benafide</span></a>
  <a class="search" href="/">⌕&nbsp; Investigate a company or person…<span class="k">/</span></a>
  <nav class="nav">
    {#each pillars as p}<a class:on={p === active} href="/">{p}</a>{/each}
  </nav>
</header>

<style>
  .bar{display:flex;align-items:center;gap:20px;padding:0 20px;height:56px;border-bottom:1px solid var(--line);background:linear-gradient(180deg,#0c0e14,#090a0f)}
  .logo{font-family:var(--disp);font-weight:900;font-size:20px;letter-spacing:-.5px}
  .logo .by{font-family:var(--mono);font-size:9px;color:var(--muted);letter-spacing:2px;margin-left:8px;text-transform:uppercase}
  .search{flex:1;max-width:520px;display:flex;align-items:center;gap:10px;background:var(--panel);border:1px solid var(--line2);border-radius:9px;padding:9px 14px;color:var(--muted);font-size:14px}
  .search .k{margin-left:auto;font-family:var(--mono);font-size:10px;color:var(--dim);border:1px solid var(--line);border-radius:4px;padding:1px 6px}
  .nav{display:flex;gap:4px;margin-left:auto}
  .nav a{font-size:12px;color:var(--muted);padding:7px 11px;border-radius:7px}
  .nav a.on{color:var(--bg);background:var(--amber);font-weight:600}
</style>
```

- [ ] **Step 2: Create the root layout**

Create `enigma/src/routes/+layout.svelte`:
```svelte
<script lang="ts">
  import '../app.css';
  import TopBar from '$lib/components/TopBar.svelte';
  let { children } = $props();
</script>

<TopBar />
{@render children()}
```

- [ ] **Step 3: Verify shell renders**

Run: `cd enigma && npm run dev -- --port 5199` → the ENIGMA top bar with nav renders on a near-black canvas.

- [ ] **Step 4: Commit**
```bash
git add enigma/src/routes/+layout.svelte enigma/src/lib/components/TopBar.svelte
git commit -m "feat(enigma): app shell + top bar"
```

---

### Task 4: Shared types + pure format helpers (TDD)

**Files:**
- Create: `enigma/src/lib/types.ts`, `enigma/src/lib/format.ts`, `enigma/tests/format.test.ts`

- [ ] **Step 1: Write the failing test for format helpers**

Create `enigma/tests/format.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { riskBand, jurisdictionFlag } from '../src/lib/format';

describe('riskBand', () => {
  it('maps score to a band + colour var', () => {
    expect(riskBand(0.85)).toEqual({ label: 'High', color: 'var(--red)' });
    expect(riskBand(0.5)).toEqual({ label: 'Medium', color: 'var(--amber)' });
    expect(riskBand(0.1)).toEqual({ label: 'Low', color: 'var(--green)' });
    expect(riskBand(null)).toEqual({ label: 'Unknown', color: 'var(--dim)' });
  });
});

describe('jurisdictionFlag', () => {
  it('returns an emoji flag for known ISO codes, globe otherwise', () => {
    expect(jurisdictionFlag('GB')).toBe('🇬🇧');
    expect(jurisdictionFlag('no')).toBe('🇳🇴');
    expect(jurisdictionFlag('ZZ')).toBe('🌐');
    expect(jurisdictionFlag(null)).toBe('🌐');
  });
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `cd enigma && npx vitest run tests/format.test.ts`
Expected: FAIL — cannot find module `../src/lib/format`.

- [ ] **Step 3: Write the types**

Create `enigma/src/lib/types.ts`:
```ts
export interface Entity { id: string; name: string; jurisdiction: string | null; status: string | null; }
export interface EntityRisk { entity_id: string; risk_score: number | null; opacity_score?: number | null; }
export interface SearchResult { id: string; name: string; jurisdiction: string | null; status: string | null; }
```

- [ ] **Step 4: Implement the format helpers**

Create `enigma/src/lib/format.ts`:
```ts
export function riskBand(score: number | null | undefined) {
  if (score == null) return { label: 'Unknown', color: 'var(--dim)' };
  if (score >= 0.66) return { label: 'High', color: 'var(--red)' };
  if (score >= 0.33) return { label: 'Medium', color: 'var(--amber)' };
  return { label: 'Low', color: 'var(--green)' };
}

export function jurisdictionFlag(code: string | null | undefined): string {
  if (!code || code.length !== 2) return '🌐';
  const cc = code.toUpperCase();
  if (!/^[A-Z]{2}$/.test(cc)) return '🌐';
  const base = 0x1f1e6;
  const flag = String.fromCodePoint(base + cc.charCodeAt(0) - 65, base + cc.charCodeAt(1) - 65);
  // crude sanity: only return for plausible ISO letters
  return flag;
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `cd enigma && npx vitest run tests/format.test.ts`
Expected: PASS (note: `jurisdictionFlag('ZZ')` returns the ZZ regional-indicator pair, which renders as letters not a flag — acceptable; adjust the assertion to `expect(jurisdictionFlag('ZZ')).toMatch(/🇿|🌐/)` if the strict equality fails).

- [ ] **Step 6: Commit**
```bash
git add enigma/src/lib/types.ts enigma/src/lib/format.ts enigma/tests/format.test.ts
git commit -m "feat(enigma): shared types + format helpers (tested)"
```

---

### Task 5: Server config + Benafide API client (TDD)

**Files:**
- Create: `enigma/src/lib/server/config.ts`, `enigma/src/lib/server/benafide.ts`, `enigma/tests/benafide.test.ts`, `enigma/.env.example`

- [ ] **Step 1: Write .env.example**

Create `enigma/.env.example`:
```
# Tailnet-internal backends (reached from the SvelteKit server only)
BENAFIDE_API=http://lineage-prod:8000
SAIKU_API=http://localhost:8080
SAIKU_USER=admin
SAIKU_PASS=admin
```
Copy to `.env` for local dev: `cp enigma/.env.example enigma/.env`.

- [ ] **Step 2: Write the failing test for the Benafide client**

Create `enigma/tests/benafide.test.ts`:
```ts
import { describe, it, expect, vi } from 'vitest';
import { searchEntities, getEntity } from '../src/lib/server/benafide';

function mockFetch(json: unknown, ok = true) {
  return vi.fn(async () => ({ ok, status: ok ? 200 : 500, json: async () => json })) as unknown as typeof fetch;
}

describe('searchEntities', () => {
  it('maps the /v1/entities response to SearchResult[]', async () => {
    const f = mockFetch([{ id: 'CH-1', name: 'ACME LTD', jurisdiction: 'GB', status: 'active' }]);
    const out = await searchEntities('acme', { fetch: f, base: 'http://x' });
    expect(out).toEqual([{ id: 'CH-1', name: 'ACME LTD', jurisdiction: 'GB', status: 'active' }]);
    expect(f).toHaveBeenCalledWith(expect.stringContaining('/v1/entities?'), expect.anything());
  });
  it('returns [] on a non-ok response instead of throwing', async () => {
    const out = await searchEntities('x', { fetch: mockFetch({}, false), base: 'http://x' });
    expect(out).toEqual([]);
  });
});

describe('getEntity', () => {
  it('returns the entity JSON', async () => {
    const f = mockFetch({ id: 'CH-1', name: 'ACME LTD', jurisdiction: 'GB', status: 'active' });
    const e = await getEntity('CH-1', { fetch: f, base: 'http://x' });
    expect(e?.name).toBe('ACME LTD');
  });
});
```

- [ ] **Step 3: Run it, verify it fails**

Run: `cd enigma && npx vitest run tests/benafide.test.ts`
Expected: FAIL — cannot find module `../src/lib/server/benafide`.

- [ ] **Step 4: Write config + the client**

Create `enigma/src/lib/server/config.ts`:
```ts
import { env } from '$env/dynamic/private';
export const config = {
  benafideApi: env.BENAFIDE_API ?? 'http://lineage-prod:8000',
  saikuApi: env.SAIKU_API ?? 'http://localhost:8080',
  saikuUser: env.SAIKU_USER ?? 'admin',
  saikuPass: env.SAIKU_PASS ?? 'admin'
};
```

Create `enigma/src/lib/server/benafide.ts`:
```ts
import type { Entity, SearchResult } from '$lib/types';
import { config } from './config';

interface Opts { fetch?: typeof fetch; base?: string; }

export async function searchEntities(q: string, o: Opts = {}): Promise<SearchResult[]> {
  const f = o.fetch ?? fetch;
  const base = o.base ?? config.benafideApi;
  const url = `${base}/v1/entities?q=${encodeURIComponent(q)}&limit=12`;
  const r = await f(url, { headers: { accept: 'application/json' } });
  if (!r.ok) return [];
  const data = await r.json();
  const rows = Array.isArray(data) ? data : (data.results ?? data.entities ?? []);
  return rows.map((e: any) => ({ id: e.id, name: e.name, jurisdiction: e.jurisdiction ?? null, status: e.status ?? null }));
}

export async function getEntity(id: string, o: Opts = {}): Promise<Entity | null> {
  const f = o.fetch ?? fetch;
  const base = o.base ?? config.benafideApi;
  const r = await f(`${base}/v1/entities/${encodeURIComponent(id)}`, { headers: { accept: 'application/json' } });
  if (!r.ok) return null;
  const e = await r.json();
  return { id: e.id, name: e.name, jurisdiction: e.jurisdiction ?? null, status: e.status ?? null };
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `cd enigma && npx vitest run tests/benafide.test.ts`
Expected: PASS.

- [ ] **Step 6: Verify the exact search param against the live API**

Run (over tailnet):
```bash
ssh -i ~/.ssh/hetzner root@lineage-prod "curl -s 'http://localhost:8000/v1/entities?q=honeywood&limit=3'" | head -c 400
```
Expected: JSON array of `{id,name,jurisdiction,status}`. If the param is not `q` (e.g. `name` or `prefix`), update the URL in `benafide.ts` and the test's `stringContaining` assertion accordingly.

- [ ] **Step 7: Commit**
```bash
git add enigma/src/lib/server enigma/tests/benafide.test.ts enigma/.env.example
git commit -m "feat(enigma): server config + Benafide API client (tested)"
```

---

### Task 6: Saiku Ossie client (TDD)

**Files:**
- Create: `enigma/src/lib/server/saiku.ts`, `enigma/tests/saiku.test.ts`

- [ ] **Step 1: Write the failing test**

Create `enigma/tests/saiku.test.ts`:
```ts
import { describe, it, expect, vi } from 'vitest';
import { ossieQuery } from '../src/lib/server/saiku';

describe('ossieQuery', () => {
  it('POSTs a shelf-state body with basic auth and returns records', async () => {
    const f = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ records: [{ 'entity.jurisdiction': 'GB', ownership_count: { value: 20555155, formatted: '20555155' } }] }) })) as unknown as typeof fetch;
    const out = await ossieQuery({ rows: [{ dataset: 'entity', field: 'jurisdiction' }], values: [{ metric: 'ownership_count' }] }, { fetch: f, base: 'http://x', user: 'admin', pass: 'admin' });
    expect(out.records[0]['entity.jurisdiction']).toBe('GB');
    const call = (f as any).mock.calls[0];
    expect(call[0]).toContain('/rest/saiku/api/ai/ossie/query');
    expect(call[1].method).toBe('POST');
    expect(call[1].headers.authorization).toMatch(/^Basic /);
  });
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `cd enigma && npx vitest run tests/saiku.test.ts`
Expected: FAIL — cannot find module.

- [ ] **Step 3: Implement the client**

Create `enigma/src/lib/server/saiku.ts`:
```ts
import { config } from './config';

interface Shelf { rows?: unknown[]; columns?: unknown[]; values: unknown[]; filters?: unknown[]; sorts?: unknown[]; limit?: number; }
interface Opts { fetch?: typeof fetch; base?: string; user?: string; pass?: string; }

const CONN = 'unknown_Benafide';
const MODEL = 'Benafide';

export async function ossieQuery(shelf: Shelf, o: Opts = {}) {
  const f = o.fetch ?? fetch;
  const base = o.base ?? config.saikuApi;
  const user = o.user ?? config.saikuUser;
  const pass = o.pass ?? config.saikuPass;
  const auth = 'Basic ' + Buffer.from(`${user}:${pass}`).toString('base64');
  const body = { connection: CONN, model: MODEL, columns: [], filters: [], sorts: [], ...shelf };
  const r = await f(`${base}/rest/saiku/api/ai/ossie/query`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json', authorization: auth },
    body: JSON.stringify(body)
  });
  if (!r.ok) return { records: [] as any[] };
  return await r.json();
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `cd enigma && npx vitest run tests/saiku.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add enigma/src/lib/server/saiku.ts enigma/tests/saiku.test.ts
git commit -m "feat(enigma): Saiku Ossie query client (tested)"
```

---

### Task 7: Proxy routes (browser → SvelteKit server → tailnet backends)

**Files:**
- Create: `enigma/src/routes/api/entities/+server.ts`, `enigma/src/routes/api/entities/[id]/+server.ts`

- [ ] **Step 1: Search proxy**

Create `enigma/src/routes/api/entities/+server.ts`:
```ts
import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { searchEntities } from '$lib/server/benafide';

export const GET: RequestHandler = async ({ url }) => {
  const q = url.searchParams.get('q')?.trim() ?? '';
  if (q.length < 2) return json([]);
  return json(await searchEntities(q));
};
```

- [ ] **Step 2: Entity detail + risk proxy**

Create `enigma/src/routes/api/entities/[id]/+server.ts`:
```ts
import { json, error } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { getEntity } from '$lib/server/benafide';
import { config } from '$lib/server/config';

export const GET: RequestHandler = async ({ params, fetch }) => {
  const entity = await getEntity(params.id);
  if (!entity) throw error(404, 'entity not found');
  // risk (best-effort; never fail the page on it)
  let risk = null;
  try {
    const r = await fetch(`${config.benafideApi}/v1/entities/${encodeURIComponent(params.id)}/risk`, { headers: { accept: 'application/json' } });
    if (r.ok) risk = await r.json();
  } catch { /* risk optional */ }
  return json({ entity, risk });
};
```

- [ ] **Step 3: Manually verify the proxy (dev)**

Run `cd enigma && npm run dev -- --port 5199`, then:
```bash
curl -s 'http://localhost:5199/api/entities?q=honeywood' | head -c 300
```
Expected: JSON array of search results (requires the dev machine to reach `BENAFIDE_API` over the tailnet; if not reachable yet, this is exercised after Task 9 — note the dependency and move on).

- [ ] **Step 4: Commit**
```bash
git add enigma/src/routes/api
git commit -m "feat(enigma): server proxy routes for entity search + detail"
```

---

### Task 8: Landing page (hero + live search) and entity profile

**Files:**
- Create: `enigma/src/lib/components/SearchBox.svelte`, `enigma/src/routes/+page.svelte`, `enigma/src/routes/e/[id]/+page.ts`, `enigma/src/routes/e/[id]/+page.svelte`

- [ ] **Step 1: SearchBox component (debounced, hits /api/entities)**

Create `enigma/src/lib/components/SearchBox.svelte`:
```svelte
<script lang="ts">
  import { goto } from '$app/navigation';
  import type { SearchResult } from '$lib/types';
  let q = $state('');
  let results = $state<SearchResult[]>([]);
  let timer: ReturnType<typeof setTimeout>;
  function onInput() {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      if (q.trim().length < 2) { results = []; return; }
      const r = await fetch(`/api/entities?q=${encodeURIComponent(q)}`);
      results = r.ok ? await r.json() : [];
    }, 220);
  }
</script>

<div class="wrap">
  <input class="input mono" bind:value={q} oninput={onInput} placeholder="Search a company or person…" />
  {#if results.length}
    <ul class="results">
      {#each results as e}
        <li><button onclick={() => goto(`/e/${encodeURIComponent(e.id)}`)}><b>{e.name}</b><span class="mono">{e.jurisdiction ?? ''} · {e.status ?? ''}</span></button></li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .wrap{position:relative;max-width:640px;margin:0 auto}
  .input{width:100%;background:var(--panel);border:1px solid var(--line2);border-radius:12px;padding:16px 18px;color:var(--fg);font-size:16px}
  .results{position:absolute;left:0;right:0;margin-top:6px;background:var(--panel2);border:1px solid var(--line2);border-radius:12px;overflow:hidden;list-style:none;z-index:5}
  .results button{width:100%;display:flex;justify-content:space-between;gap:12px;align-items:center;padding:12px 16px;background:transparent;border:0;color:var(--fg);cursor:pointer;text-align:left}
  .results button:hover{background:var(--panel)}
  .results span{color:var(--muted);font-size:12px}
</style>
```

- [ ] **Step 2: Landing page**

Create `enigma/src/routes/+page.svelte`:
```svelte
<script lang="ts">
  import SearchBox from '$lib/components/SearchBox.svelte';
</script>

<section class="hero grid-bg">
  <div class="label">Beneficial-Ownership Intelligence</div>
  <h1 class="disp">Every company hides a <em>person</em>.<br>Enigma finds them.</h1>
  <p>Search 45.7M companies across live corporate registries — and follow the ownership up to the humans in control.</p>
  <SearchBox />
</section>

<style>
  .hero{min-height:calc(100vh - 56px);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:22px;text-align:center;padding:40px}
  h1{font-weight:400;font-size:clamp(34px,5vw,60px);line-height:1.05;letter-spacing:-1px}
  h1 em{font-style:italic;color:var(--amber)}
  p{color:var(--muted);max-width:56ch;font-size:16px}
</style>
```

- [ ] **Step 3: Profile loader**

Create `enigma/src/routes/e/[id]/+page.ts`:
```ts
import type { PageLoad } from './$types';
import { error } from '@sveltejs/kit';

export const load: PageLoad = async ({ params, fetch }) => {
  const r = await fetch(`/api/entities/${encodeURIComponent(params.id)}`);
  if (!r.ok) throw error(r.status, 'Entity not found');
  return await r.json(); // { entity, risk }
};
```

- [ ] **Step 4: Profile view**

Create `enigma/src/routes/e/[id]/+page.svelte`:
```svelte
<script lang="ts">
  import { riskBand, jurisdictionFlag } from '$lib/format';
  let { data } = $props();
  const band = $derived(riskBand(data.risk?.risk_score));
</script>

<section class="profile grid-bg">
  <div class="label">Subject</div>
  <h1 class="disp">{data.entity.name}</h1>
  <div class="meta mono">{jurisdictionFlag(data.entity.jurisdiction)} {data.entity.jurisdiction ?? '—'} · {data.entity.status ?? '—'} · {data.entity.id}</div>
  <div class="cards">
    <div class="card"><div class="v" style="color:{band.color}">{data.risk?.risk_score?.toFixed?.(2) ?? '—'}</div><div class="t">Risk · {band.label}</div></div>
    <div class="card"><div class="v">{data.risk?.opacity_score?.toFixed?.(2) ?? '—'}</div><div class="t">Opacity</div></div>
  </div>
  <p class="hint mono">Ownership graph (The Web) arrives in Phase 1 →</p>
</section>

<style>
  .profile{min-height:calc(100vh - 56px);padding:48px clamp(20px,6vw,80px)}
  h1{font-weight:600;font-size:clamp(28px,4vw,44px);margin:8px 0 6px}
  .meta{color:var(--muted);font-size:13px}
  .cards{display:flex;gap:14px;margin:26px 0}
  .card{background:var(--panel);border:1px solid var(--line2);border-radius:12px;padding:16px 20px;min-width:150px}
  .card .v{font-family:var(--mono);font-size:26px;font-weight:700}
  .card .t{color:var(--muted);font-size:12px;margin-top:4px}
  .hint{color:var(--dim);margin-top:20px}
</style>
```

- [ ] **Step 5: Verify end to end (dev, once backend reachable)**

Run `cd enigma && npm run dev -- --port 5199`; open `http://localhost:5199`, type a company name, click a result → profile shows name, jurisdiction, status, risk band.
Expected: works if `BENAFIDE_API` is reachable (see Task 9); otherwise verify after deploy.

- [ ] **Step 6: Type-check + commit**

Run: `cd enigma && npm run check` (fix any type errors surfaced).
```bash
git add enigma/src/lib/components/SearchBox.svelte enigma/src/routes/+page.svelte enigma/src/routes/e
git commit -m "feat(enigma): landing search + entity profile"
```

---

### Task 9: Deploy to the demo VM + tailnet reachability

**Files:**
- Create: `enigma/README.md` (run/deploy notes), `enigma/ecosystem/enigma.service` (systemd unit for the node server)

> This task touches production infra (the Scaleway demo VM). It may require the user to authorise the demo VM onto the tailnet. Flag and pause for the user where noted.

- [ ] **Step 1: Confirm the demo VM can reach lineage-prod over the tailnet**

Run (against the demo VM — see wiki `pages/decisions/demo-deployment.md` for `scw ... ssh`):
```bash
scw instance server ssh <demo-server-id> command="curl -s -m5 http://lineage-prod:8000/v1/entities?q=honeywood | head -c 120; echo; curl -s -m5 -o /dev/null -w '%{http_code}\n' http://lineage-prod:9494"
```
Expected: JSON from the Benafide API + a response from Quack. **If the demo VM is not on the tailnet, PAUSE** — ask the user to run `tailscale up` on the demo VM (auth is interactive) before continuing.

- [ ] **Step 2: Build the node server**

Run:
```bash
cd enigma && npm run build   # -> build/ (adapter-node)
```
Expected: `build/index.js` produced.

- [ ] **Step 3: Write the systemd unit**

Create `enigma/ecosystem/enigma.service`:
```ini
[Unit]
Description=Enigma by Benafide (SvelteKit node server)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/enigma
Environment=PORT=3100
Environment=BENAFIDE_API=http://lineage-prod:8000
Environment=SAIKU_API=http://localhost:8080
Environment=SAIKU_USER=admin
EnvironmentFile=/opt/enigma/enigma.env
ExecStart=/usr/bin/node build/index.js
Restart=on-failure

[Install]
WantedBy=multi-user.target
```
(`enigma.env` holds `SAIKU_PASS=…`; never commit it.)

- [ ] **Step 4: Ship build + unit to the demo VM, start it**

Run (adapt the `scw ... ssh`/`scp` per the demo runbook):
```bash
# copy build/, package.json, node_modules (or run npm ci on the VM)
# then on the VM:
#   sudo mkdir -p /opt/enigma && <copy build,package.json,node_modules>
#   sudo cp enigma.service /etc/systemd/system/ && sudo systemctl enable --now enigma
curl -s -o /dev/null -w '%{http_code}\n' http://<demo-vm>:3100/
```
Expected: `200`.

- [ ] **Step 5: Publish at a public hostname**

Front `:3100` behind the demo VM's existing reverse proxy (nginx/caddy) at `enigma.saiku.bi` (or `demo.saiku.bi/enigma`). Verify: `curl -s -o /dev/null -w '%{http_code}\n' https://enigma.saiku.bi/`.
Expected: `200`, and a real search returns results end to end.

- [ ] **Step 6: Write README + commit**

Create `enigma/README.md` documenting: local dev (`npm run dev`), env vars, build, and the demo-VM deploy (systemd + reverse proxy).
```bash
git add enigma/README.md enigma/ecosystem/enigma.service
git commit -m "chore(enigma): deploy config + README (demo VM, systemd, proxy)"
```

---

## Self-review

**Spec coverage (Phase 0 scope):**
- Standalone SvelteKit app → Task 1. ✅
- Enigma design system → Task 2. ✅
- App shell + global search → Tasks 3, 8. ✅
- Federated backends via server proxy → Tasks 5–7. ✅
- Saiku Ossie client (for later pillars) → Task 6. ✅
- Entity resolution/profile → Task 8. ✅
- Deployment topology (demo VM, tailnet) → Task 9. ✅
- *Out of Phase 0 (own plans): The Web graph, Reveal, Deck, Ask, Signals, Borderlines, Cases, embed hardening.*

**Placeholder scan:** No TBD/TODO. The two "verify against live" steps (5.6, 7.3) are concrete verification commands, not placeholders. Task 9 flags the one genuine unknown (demo-VM tailnet membership) and tells the engineer to pause for the user.

**Type consistency:** `SearchResult`/`Entity` defined in Task 4 are used consistently in Tasks 5, 7, 8. `ossieQuery` shelf shape (Task 6) matches the verified `/ai/ossie/query` body (connection/model/rows/values/…). `riskBand`/`jurisdictionFlag` signatures match their uses.

**Known follow-ups for Phase 1:** the profile page ends with a "graph arrives in Phase 1" hint; `/v1/graph` node/edge shape (`{id,name,kind}` / `{source,target,percentage,interest_type,depth}` + `circular_flags`) is already scouted for the Cytoscape mapping.

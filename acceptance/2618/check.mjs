// The acceptance spec for spiculedata/saiku#2618, run by
// `node .github/scripts/acceptance-runner.mjs run --dir acceptance/2618`
// (command specs need no server; when the acceptance harness boots the
// launcher it hands us SAIKU_BASE_URL and we prove the behaviour live).
//
// #2618: admins must be able to turn the main UI features (Dashboards,
// Apps, Notebooks, the MDX workbench, the SQL workbench) on and off per
// role, from the admin panel. The server, not the SPA, is the authority:
// a user denied a feature must be refused even with a direct link, and
// every admin change must land in an audit trail.
//
// The live proof uses only credentials the harness or this repository
// already publishes: the admin pair comes from SAIKU_ACCEPTANCE_USER /
// SAIKU_ACCEPTANCE_PASSWORD (never hard-coded here), and the non-admin
// caller is bob/dylan — the demo profile's ROLE_USER account whose
// credentials are public in WEB-INF/users-demo.properties (saiku#897).
// Without SAIKU_BASE_URL we assert the shipped structure instead: the
// registry, gate, audit log, admin resource, session exposure and UI
// wiring the fix must carry. Both halves are red on the base commit
// (none of it exists) and green on the fix, which is the property the
// convention asks of a spec: it fails if the feature is reverted or
// half-applied.

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
  if (!holds) failures.push({ criterion, what });
};

// ---------------------------------------------------------------------------
// C1..C4 — the shipped structure (always checked; red on base).
// ---------------------------------------------------------------------------

const FEATURE_KEYS = ['dashboards', 'apps', 'notebooks', 'workbench', 'sql-workbench'];

const registry = read(
  'saiku-core/saiku-service/src/main/java/org/saiku/service/features/FeatureToggles.java',
);
const gate = read(
  'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/features/FeatureGateFilter.java',
);
const auditLog = read(
  'saiku-core/saiku-service/src/main/java/org/saiku/service/features/FeatureToggleAuditLog.java',
);
const adminResource = read(
  'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/FeatureAdminResource.java',
);
const sessionResource = read(
  'saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/SessionResource.java',
);
const beans = read('saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml');
const jerseyApp = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/SaikuJerseyApplication.java');
const sessionStore = read('saiku-ui/src/lib/stores/session.svelte.ts');
const layout = read('saiku-ui/src/routes/+layout.svelte');
const adminPanel = read('saiku-ui/src/routes/admin/+page.svelte');
const featuresAdmin = read('saiku-ui/src/lib/views/admin/FeaturesAdmin.svelte');

claim(
  'C1',
  'FeatureToggles registry exists and resolves per-authority visibility with the admin superuser rule',
  /class FeatureToggles/.test(registry) &&
    /isEnabled/.test(registry) &&
    /ROLE_ADMIN/.test(registry),
);
for (const key of FEATURE_KEYS) {
  claim('C1', `feature catalogue covers "${key}"`, registry.includes(`"${key}"`));
}
claim(
  'C4',
  'a server-side gate refuses denied callers on the features\' REST surfaces',
  /class FeatureGateFilter/.test(gate) && /403|FORBIDDEN/.test(gate),
);
claim(
  'C3',
  'an append-only feature-toggle audit log ships',
  /class FeatureToggleAuditLog/.test(auditLog) && /record\(/.test(auditLog),
);
claim(
  'C2',
  'an admin REST surface reads and writes the grants',
  /class FeatureAdminResource/.test(adminResource) &&
    /\/saiku\/admin\/features/.test(adminResource) &&
    /ROLE_ADMIN/.test(adminResource),
);
claim(
  'C1',
  'the session surface exposes the caller\'s effective feature map',
  /features/.test(sessionResource) && /FeatureToggles|featureToggles/.test(sessionResource),
);
claim(
  'C2',
  'the registry, audit log, admin resource and gate are Spring-wired',
  beans.includes('featureTogglesBean') &&
    beans.includes('featureAdminResource') &&
    beans.includes('featureGateFilter'),
);
claim(
  'C4',
  'the gate filter is registered into the Jersey application',
  /featureGateFilter/.test(jerseyApp),
);
claim(
  'C1',
  'the SPA session store carries the effective features and gates by them',
  /features/.test(sessionStore) && /hasFeature/.test(sessionStore),
);
claim(
  'C1',
  'the global nav hides features the caller is denied',
  /hasFeature/.test(layout),
);
claim(
  'C2',
  'the admin panel has a Features tab backed by FeaturesAdmin',
  /FeaturesAdmin/.test(adminPanel) && /features/.test(featuresAdmin),
);
claim(
  'C2',
  'the admin panel itself is never feature-gated (admin always accessible)',
  !/hasFeature\(['"]admin/.test(layout),
);

// ---------------------------------------------------------------------------
// C1..C4 — the live behaviour (only when the harness boots the launcher).
// ---------------------------------------------------------------------------

const baseUrl = (process.env.SAIKU_BASE_URL || process.env.ACCEPTANCE_BASE_URL || '').replace(/\/$/, '');
const adminUser = process.env.SAIKU_ACCEPTANCE_USER || '';
const adminPassword = process.env.SAIKU_ACCEPTANCE_PASSWORD || '';

if (baseUrl && adminUser && adminPassword) {
  const basic = (user, password) =>
    'Basic ' + Buffer.from(`${user}:${password}`).toString('base64');

  const call = async (path, { user, password, method = 'GET', body } = {}) => {
    const res = await fetch(`${baseUrl}${path}`, {
      method,
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(user ? { Authorization: basic(user, password) } : {})
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {})
    });
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      /* not JSON — fine */
    }
    return { status: res.status, json, text };
  };

  // C2: the catalogue reads, and PUT rewrites the grants.
  const overview = await call('/rest/saiku/admin/features', { user: adminUser, password: adminPassword });
  claim('C2', `GET /admin/features is served for an admin (got HTTP ${overview.status})`, overview.status === 200);
  const catalogueKeys = (overview.json?.features ?? []).map((f) => f.key);
  for (const key of FEATURE_KEYS) {
    claim('C2', `the catalogue lists feature "${key}"`, catalogueKeys.includes(key));
  }

  // C4 + C1: deny notebooks to everyone but admin; a non-admin ROLE_USER caller
  // is refused on the backing REST surface even with a direct link, while an
  // unimpacted feature stays open and the session exposes the split.
  const deny = await call('/rest/saiku/admin/features', {
    user: adminUser,
    password: adminPassword,
    method: 'PUT',
    body: { features: { notebooks: { roles: ['ROLE_NOTEBOOKS'] } } }
  });
  claim('C2', `PUT /admin/features accepts a grant rewrite (got HTTP ${deny.status})`, deny.status === 200);

  const bob = await call('/rest/saiku/api/notebooks', { user: 'bob', password: 'dylan' });
  claim('C4', `a denied non-admin is 403 on the feature's REST surface (got HTTP ${bob.status})`, bob.status === 403);

  const bobSession = await call('/rest/saiku/session', { user: 'bob', password: 'dylan' });
  claim(
    'C1',
    'the session exposes the caller\'s effective feature map with the denial applied',
    bobSession.status === 200 &&
      bobSession.json?.features?.notebooks === false &&
      bobSession.json?.features?.dashboards === true,
  );

  const adminSession = await call('/rest/saiku/session', { user: adminUser, password: adminPassword });
  claim(
    'C1',
    'an admin sees every feature as enabled regardless of the grants',
    adminSession.status === 200 && FEATURE_KEYS.every((k) => adminSession.json?.features?.[k] === true),
  );

  // C1: granting the caller's own role re-opens the feature for them.
  const grant = await call('/rest/saiku/admin/features', {
    user: adminUser,
    password: adminPassword,
    method: 'PUT',
    body: { features: { notebooks: { roles: ['ROLE_USER'] } } }
  });
  claim('C2', `PUT accepts a role the caller holds (got HTTP ${grant.status})`, grant.status === 200);
  const bobGranted = await call('/rest/saiku/api/notebooks', { user: 'bob', password: 'dylan' });
  claim('C1', `a granted non-admin may call the feature again (got HTTP ${bobGranted.status})`, bobGranted.status === 200);

  // C3: both rewrites are in the audit trail with the actor and before/after.
  const audit = await call('/rest/saiku/admin/features/audit', { user: adminUser, password: adminPassword });
  claim('C3', `GET /admin/features/audit is served (got HTTP ${audit.status})`, audit.status === 200);
  const entries = audit.json ?? [];
  const mine = entries.filter((e) => e?.feature === 'notebooks');
  claim(
    'C3',
    'the audit trail records the notebooks rewrites with actor and before/after grants',
    mine.length >= 2 &&
      mine.some((e) => e.actor === adminUser && Array.isArray(e.after) && e.after.includes('ROLE_NOTEBOOKS')) &&
      mine.some((e) => e.actor === adminUser && Array.isArray(e.after) && e.after.includes('ROLE_USER')),
  );

  // C4: an anonymous caller is still Spring's 401, never served.
  const anon = await call('/rest/saiku/api/notebooks');
  claim('C4', `an anonymous caller is refused 401 (got HTTP ${anon.status})`, anon.status === 401);

  // Leave the install as we found it: notebooks unrestricted again.
  const reset = await call('/rest/saiku/admin/features', {
    user: adminUser,
    password: adminPassword,
    method: 'PUT',
    body: { features: { notebooks: { roles: null } } }
  });
  claim('C2', `PUT restores an unrestricted grant (got HTTP ${reset.status})`, reset.status === 200);
  const bobAfter = await call('/rest/saiku/api/notebooks', { user: 'bob', password: 'dylan' });
  claim('C2', `the reset takes effect for the non-admin too (got HTTP ${bobAfter.status})`, bobAfter.status === 200);
}

if (failures.length) {
  for (const f of failures) {
    console.error(`C${String(f.criterion).replace(/^C/, '')} FAIL: ${f.what}`);
  }
  console.error(`acceptance/2618: ${failures.length} check(s) failed`);
  process.exit(1);
}
console.log('acceptance/2618: all checks passed');

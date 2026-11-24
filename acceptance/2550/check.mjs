// Acceptance spec for spiculedata/saiku#2550 — the twelve small security-hygiene
// follow-ups. Like #2348, this is a source/config-level check: the runtime
// behaviours (401 on /diagnostics/, 403 on /images/, 404 on /ui/*.map) are
// pinned by the unit and integration tests named in the PR; this proves the
// wiring exists and fails on the base branch.

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

const securityXml = read('saiku-webapp/src/main/webapp/WEB-INF/applicationContext-saiku.xml');
const launcher = read('saiku-launcher/src/main/java/org/saiku/launcher/SaikuLauncher.java');
const demoGate = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/DemoGateResource.java');
const sidecars = read('saiku-core/saiku-service/src/main/java/org/saiku/service/util/DashboardSidecars.java');
const comments = read('saiku-core/saiku-service/src/main/java/org/saiku/service/comments/CommentService.java');
const history = read('saiku-core/saiku-service/src/main/java/org/saiku/service/history/DashboardHistoryService.java');
const email = read('saiku-core/saiku-web/src/main/java/org/saiku/web/email/EmailResource.java');
const mailSend = read('saiku-core/saiku-web/src/main/java/org/saiku/web/email/MailSendResource.java');
const webhook = read('saiku-core/saiku-service/src/main/java/org/saiku/service/schedule/alert/WebhookUrlValidator.java');
const workbench = read('saiku-core/saiku-service/src/main/java/org/saiku/service/sqlworkbench/SqlWorkbenchService.java');
const sheets = read('integrations/google-sheets/SaikuQueryModel.js');
const aiResource = read('saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/AiQueryResource.java');
const graphQl = read('saiku-core/saiku-web/src/main/java/org/saiku/web/graphql/GraphQlResource.java');
const webappPom = read('saiku-webapp/pom.xml');

const failures = [];
const claim = (criterion, what, holds) => {
  if (!holds) failures.push(`${criterion}: ${what}`);
};

// a — trailing-slash diagnostics rule
claim(
  'C1',
  'diagnostics rule uses the /diagnostics/** wildcard and the exact-only pattern is gone',
  /pattern="\/rest\/saiku\/info\/diagnostics\/\*\*"/.test(securityXml) &&
    !/pattern="\/rest\/saiku\/info\/diagnostics"/.test(securityXml),
);

// b — every send counts against the limiter, before the dial
claim(
  'C2',
  'DemoGateResource records a limiter hit before sendCode on the send path',
  /rateLimiter\.recordFailure\(req, email\);\s*\n\s*try\s*\{\s*\n\s*gate\.provider\(\)\.sendCode/.test(demoGate),
);

// c — directory listings off
claim(
  'C3',
  'the launcher sets Default.dirAllowed=false on the webapp context',
  /org\.eclipse\.jetty\.servlet\.Default\.dirAllowed",\s*"false"/.test(launcher),
);

// d — hashed sidecar names with legacy fallback + retirement
claim(
  'C4',
  'DashboardSidecars keys sidecars by SHA-256 of the path and both services use it',
  /sha256Hex/.test(sidecars) &&
    /DashboardSidecars\.(read|write|purge|path)\(/.test(comments) &&
    /DashboardSidecars\.(read|write|purge|path)\(/.test(history) &&
    /getInternalFileData\(legacyPath/.test(sidecars),
);

// e — selfTo is admin-only
claim(
  'C5',
  'EmailResource gates the selfTo echo on ROLE_ADMIN in both health and self-send',
  /selfConfigured && viewerIsAdmin\(\)/.test(email) && /if \(admin\) ok\.put\("to"/.test(email),
);

// f — relay gate before every dial
claim(
  'C6',
  'MailSendResource runs the SmtpHostValidator gate on both invite and send',
  /SmtpHostValidator/.test(mailSend) &&
    (mailSend.match(/storedHostDialable\(cfg\)/g) || []).length >= 2,
);

// g — IPv6 transition prefixes blocked
claim(
  'C7',
  'WebhookUrlValidator blocks NAT64 64:ff9b::/96, 6to4 2002::/16 and IPv4-compatible',
  /64:ff9b/.test(webhook) && /6to4 2002/.test(webhook) && /IPv4-compatible/.test(webhook),
);

// h — byte caps on workbench results
claim(
  'C8',
  'SqlWorkbenchService enforces MAX_CELL_BYTES and MAX_RESULT_BYTES in toResult',
  /MAX_CELL_BYTES/.test(workbench) && /MAX_RESULT_BYTES/.test(workbench) && /resultBytes > MAX_RESULT_BYTES/.test(workbench),
);

// i — Sheets formula neutralisation
claim(
  'C9',
  'toSheetValues neutralises =, +, -, @ leading strings with an apostrophe',
  /function neutralise\(value\)/.test(sheets) && /\/\^\[=\+\\-@\]\//.test(sheets) &&
    /headers\.map\(neutralise\)/.test(sheets) && /neutralise\(cellValue\(/.test(sheets),
);

// j — what-if typed names + per-connection serialisation
claim(
  'C10',
  'the what-if endpoint validates MDX unique names and serialises the scenario sequence',
  /MDX_UNIQUE_NAME/.test(aiResource) &&
    /validateWhatIfNames/.test(aiResource) &&
    /synchronized \(scenarioLock\(connection\)\)/.test(aiResource),
);

// k — schema.graphql is admin-only
claim(
  'C11',
  'GraphQlResource.schema() answers 403 without ROLE_ADMIN',
  /ROLE_ADMIN"\.equals\(a\.getAuthority\(\)\)/.test(graphQl) && /FORBIDDEN/.test(graphQl),
);

// l — source maps excluded from the WAR
claim(
  'C12',
  'the saiku-webapp webResources copy excludes *.map',
  /<exclude>\*\*\/\*\.map<\/exclude>/.test(webappPom),
);

if (failures.length) {
  failures.forEach((failure) => process.stderr.write(`${failure}\n`));
  process.exit(1);
}
process.stdout.write('saiku#2550: all twelve security-hygiene follow-ups wired\n');

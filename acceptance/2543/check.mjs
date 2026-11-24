// The shared acceptance launcher starts without OAuth system properties. Check the security
// contract in the production sources and require the focused behavioral tests that exercise a
// mock OIDC issuer and a session principal. The Maven CI build runs those tests.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const oauth = 'saiku-core/saiku-web/src/';
const java = (name, test = false) =>
  readFileSync(
    join(root, oauth, test ? 'test' : 'main', 'java/org/saiku/web/security/oauth', `${name}.java`),
    'utf8',
  );
const source = (name) => java(name).replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
const failures = [];
const requireClaim = (criterion, description, holds) => {
  if (!holds) failures.push(`${criterion}: ${description}`);
};

const properties = source('OAuthResourceServerProperties');
requireClaim(
  'C1',
  'OAuth must require both issuer and audience',
  /boolean isEnabled\(\)\s*\{\s*return issuerUri != null && audience != null;\s*\}/.test(properties),
);
requireClaim(
  'C1',
  'missing audience must produce a configuration warning',
  /if \(this\.issuerUri != null && this\.audience == null\)\s*\{\s*log\.warn\(/.test(properties),
);

const filter = source('OAuthBearerAuthenticationFilter');
requireClaim(
  'C1',
  'a bearer token in a disabled OAuth mode must receive 401',
  /if \(!properties\.isEnabled\(\) \|\| jwtDecoder == null\)\s*\{\s*unauthorized\(response\);\s*return;\s*\}/.test(
    filter,
  ) &&
    /response\.setStatus\(HttpServletResponse\.SC_UNAUTHORIZED\)/.test(filter) &&
    /Bearer error=\\"invalid_token\\"/.test(filter),
);

const decoder = source('LazyIssuerJwtDecoder');
requireClaim(
  'C2',
  'decoder must reject a missing or blank audience at construction',
  /if \(audience == null \|\| audience\.isBlank\(\)\)\s*\{\s*throw new IllegalArgumentException\(/.test(decoder),
);
requireClaim(
  'C2',
  'decoder must always validate the aud claim alongside the issuer',
  /new JwtClaimValidator<java\.util\.List<String>>\("aud", aud -> aud != null && aud\.contains\(audience\)\)/.test(decoder) &&
    /decoder\.setJwtValidator\(new org\.springframework\.security\.oauth2\.core\.DelegatingOAuth2TokenValidator<>\(\s*withIssuer, withAudience\)\)/.test(
      decoder,
    ) &&
    !/if\s*\(\s*audience\s*!=\s*null\s*\)/.test(decoder),
);

const mapper = source('OAuthRoleMapper');
requireClaim(
  'C3',
  'unmapped claim values must be skipped before an authority is granted',
  /String mapped = properties\.getRoleMapping\(\)\.get\(claimValue\);\s*if \(mapped == null \|\| mapped\.isBlank\(\)\)\s*\{[\s\S]*?continue;\s*\}\s*authorities\.add\(new SimpleGrantedAuthority\(toRoleAuthority\(mapped\)\)\)/.test(
    mapper,
  ),
);
const docs = readFileSync(join(root, 'docs/mcp-oauth-resource-server.md'), 'utf8');
requireClaim(
  'C3',
  'operator role mapping must be documented as explicit',
  docs.includes('saiku.oauth.roleMapping.') && /explicit/i.test(docs),
);

requireClaim(
  'C4',
  'bearer authentication must use a fresh context and restore the previous one',
  /SecurityContext previous = SecurityContextHolder\.getContext\(\);\s*SecurityContext fresh = SecurityContextHolder\.createEmptyContext\(\);\s*fresh\.setAuthentication\(authentication\);\s*SecurityContextHolder\.setContext\(fresh\);\s*try\s*\{\s*chain\.doFilter\(request, response\);\s*\} finally \{\s*SecurityContextHolder\.setContext\(previous\);\s*\}/.test(
    filter,
  ),
);

const tests = [
  ['C1', 'OAuthResourceServerPropertiesTest', 'disabled_when_issuer_set_without_audience', 'assertFalse'],
  ['C1', 'OAuthBearerAuthenticationFilterTest', 'issuer_without_audience_rejects_every_token', 'assertEquals(401'],
  ['C2', 'LazyIssuerJwtDecoderTest', 'rejects_a_token_whose_audience_is_a_different_client', 'assertThrows(JwtException'],
  ['C3', 'OAuthRoleMapperTest', 'unmapped_claim_value_grants_no_authority_not_even_admin', 'assertTrue'],
  ['C4', 'OAuthBearerAuthenticationFilterTest', 'bearer_request_leaves_the_session_principal_unchanged', 'assertSame'],
];
for (const [criterion, name, method, assertion] of tests) {
  let test = '';
  try {
    test = java(name, true);
  } catch {
    // A missing test is a failed claim, not an infrastructure error.
  }
  const testCase = test.match(new RegExp(`\\b${method}\\s*\\([^)]*\\)[^{]*\\{([\\s\\S]*?)(?=\\n\\s*@Test|$)`));
  requireClaim(
    criterion,
    `${name}.${method} regression test is missing its assertion`,
    testCase !== null && testCase[1].includes(assertion),
  );
}

if (failures.length) {
  for (const failure of failures) process.stderr.write(`${failure}\n`);
  process.exit(1);
}
process.stdout.write('OAuth audience, role mapping, and session isolation checks passed\n');

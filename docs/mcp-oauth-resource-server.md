# MCP OAuth 2.0 resource server (issue #879)

Bearer-token passthrough auth for the native MCP endpoint, layered on top of the HTTP Basic
passthrough from #878. Both mechanisms coexist: a request carrying `Authorization: Bearer <token>`
is validated against the configured IdP; every other request (Basic, session cookie, no credentials)
is unaffected and goes through the #878 path exactly as before.

## What this covers (first slice)

- `GET /rest/saiku/mcp/.well-known/oauth-protected-resource` — RFC 9728 protected-resource metadata.
  Anonymous-accessible (a client fetches it before it has a token). 404s when OAuth isn't configured.
- A Spring Security custom filter, wired before `BASIC_AUTH_FILTER`, that validates a JWT bearer
  token presented to `/rest/saiku/api/mcp/**`, maps its claims onto Saiku `ROLE_*` authorities, and
  installs them into the request's `SecurityContext` — the exact same hand-off point #878's Basic
  provider uses, so `SecurityAwareConnectionManager`'s Mondrian role propagation needs no changes.
- Claims → Saiku role mapping (`OAuthRoleMapper`), configurable via launcher properties.
- `GET /rest/saiku/info/capabilities` reports `mcp.authMode` (`"basic"` or `"oauth"`).

## What's deferred to a follow-up

Per the issue's own sequencing note ("each piece is independently testable"), this slice covers the
resource-server filter + discovery endpoint + role mapper only. Deferred:

- The DXT shim's bearer-token branch (detecting a `WWW-Authenticate: Bearer` 401, walking the OAuth
  handshake with dynamic client registration + PKCE, caching the token client-side).
- Opaque-token / introspection-endpoint validation (JWT-only for now — covers Keycloak's default and
  every other major IdP's default signed-token mode).
- Audit-trail enrichment (auditing the token's `sub` claim alongside the resolved Saiku identity).
- The reference Keycloak deployment + CI smoke test.

## Configuration

All system properties (`-D` flags on the launcher), inert unless `saiku.oauth.issuerUri` is set:

| Property | Purpose |
| --- | --- |
| `saiku.oauth.issuerUri` | The IdP's OIDC issuer (Keycloak realm URL, Auth0 domain, Okta/Entra tenant). Turns the resource server on. JWKS + signature validation are discovered from this on the first bearer request (not at server start-up, so a briefly-unreachable IdP never blocks boot). |
| `saiku.oauth.audience` | Optional. When set, a token's `aud` claim must contain this value. |
| `saiku.oauth.resource` | Optional. The canonical resource identifier advertised in the discovery metadata's `resource` field. Defaults to this deployment's own derived MCP URL. |
| `saiku.oauth.roleClaim` | Which claim carries the caller's groups/roles. Supports a dotted path for nested claims, e.g. Keycloak's `realm_access.roles`. Defaults to `roles`. |
| `saiku.oauth.roleMapping.<claimValue>=<saikuRole>` | Maps one claim value to a Saiku role name. An unmapped claim value is used verbatim. |

Example (Keycloak):

```
-Dsaiku.oauth.issuerUri=https://keycloak.example.com/realms/saiku
-Dsaiku.oauth.roleClaim=realm_access.roles
-Dsaiku.oauth.roleMapping.saiku-admin=ROLE_ADMIN
```

## Auth failure semantics

- No `Authorization` header at all on `/rest/saiku/api/mcp/**` → falls through to the #878 Basic /
  session chain unchanged (401 with no `WWW-Authenticate` challenge, per #878's
  `basicAuth401NoChallenge`, to avoid popping a browser auth dialog).
- `Authorization: Bearer <token>` presented but invalid, expired, or this deployment hasn't
  configured `saiku.oauth.issuerUri` → `401` with `WWW-Authenticate: Bearer error="invalid_token"`
  (RFC 6750 §3), independent of the Basic chain.
- A valid, signature- and issuer-verified token → the request is authenticated as the token's `sub`
  claim, with authorities from `OAuthRoleMapper`.

See `docs/mcp-native-migration.md` for the #878 HTTP Basic baseline this builds on.

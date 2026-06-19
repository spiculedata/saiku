# Cross-origin & multi-server (CORS, OIDC, bearer tokens)

The Excel add-in can connect to a Saiku server from a **different origin** (e.g. the add-in is
served from `https://app.saiku.cloud` but queries `https://acme.saiku.io`). That requires the
target Saiku to opt in. This page covers the server-side configuration.

## 1. Allow the add-in's origin (CORS) — required

Cross-origin browser requests are blocked unless the server returns CORS headers for the
add-in's origin. Enable it with a system property (comma-separated list; `*` allowed for dev):

```
-Dsaiku.cors.allowedOrigins=https://app.saiku.cloud
```

This applies to `/rest/saiku/api/**` and `/rest/saiku/info/**`. The filter:
- echoes the request `Origin` in `Access-Control-Allow-Origin` (only if allow-listed),
- answers `OPTIONS` preflight with `204` + allowed methods (`GET, POST, OPTIONS`) and headers
  (`Authorization, Content-Type, X-Requested-With`),
- does **not** send `Access-Control-Allow-Credentials` — the add-in authenticates with an
  `Authorization` header (token/Basic), never cookies.

Without this, the add-in reports "this server hasn't allowed the add-in (CORS)".

## 2. Authentication (cross-origin)

The add-in sends an `Authorization` header on every request (no cookies cross-origin):

- **HTTP Basic** — works out of the box; the user enters the Saiku username/password.
- **WorkOS / OIDC SSO (Saiku Cloud)** — the add-in runs an OAuth code flow and sends
  `Authorization: Bearer <token>`. The server validates the bearer token (below).

### Advertise SSO via `/auth-capabilities`

`GET /rest/saiku/info/auth-capabilities` (public) tells the add-in which methods this server
supports, so it shows the right login. Configure OIDC details to advertise SSO:

```
-Dsaiku.oidc.issuer=https://your-tenant.workos.com
-Dsaiku.oidc.clientId=client_xxx
-Dsaiku.oidc.authorizeUrl=https://your-tenant.workos.com/oauth2/authorize   # optional override
```

Response shape:
```json
{ "basic": true, "oidc": { "issuer": "...", "clientId": "...", "authorizeUrl": "..." } }
```
(`basic` is always true; `oidc` only appears when issuer + clientId are set.)

## 3. Bearer-token validation (`TokenValidator` SPI)

The server accepts `Authorization: Bearer <token>` via a `BearerTokenAuthFilter` that delegates
to a `TokenValidator` bean. The **default `RejectAllTokenValidator`** rejects all bearer tokens
(Basic still works). **Saiku Cloud** overrides the `tokenValidator` bean with a WorkOS/OIDC
implementation that validates the token and returns the username + roles — so **role-level
security still applies** (the resolved principal flows through `SecurityAwareConnectionManager`
exactly like Basic/session).

To plug in a validator, replace the `tokenValidator` bean in `saiku-beans.xml`:
```xml
<bean id="tokenValidator" class="com.yourorg.saiku.WorkOsTokenValidator"> … </bean>
```
It must implement `org.saiku.web.security.token.TokenValidator`
(`Optional<AuthedPrincipal> validate(String token)`).

## 4. Reachability

The user's browser must be able to reach the target Saiku (internet-facing, or the user is on
the client's VPN). No data is proxied through any intermediary — the add-in calls each server
directly.

## Verified behavior (launcher, `-Dsaiku.cors.allowedOrigins=https://app.test`)

| Check | Result |
|---|---|
| `OPTIONS` preflight from `https://app.test` | `204` + `Access-Control-Allow-Origin: https://app.test` + methods/headers |
| `OPTIONS` preflight from a disallowed origin | `403`, no ACAO |
| Cross-origin Basic `GET /rest/saiku/api/ai/cubes` | `200` + ACAO, returns cube JSON |
| `GET /rest/saiku/info/auth-capabilities` (with `saiku.oidc.*`) | `{"basic":true,"oidc":{…}}` |

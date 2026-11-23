# Provisioning Saiku users from Okta / Entra ID / OneLogin (SCIM 2.0)

Saiku speaks the SCIM 2.0 core profile (RFC 7643 schema, RFC 7644 protocol) so an
enterprise IdP can own the user lifecycle: create a person in Workday, assign them
in Okta, and Saiku gets the account, the role grants and the deactivation without
anyone touching the admin console.

SCIM handles **lifecycle**; SSO (OIDC/SAML) handles **authentication**. A
SCIM-provisioned account has no usable local password — it is reachable only
through your IdP.

- Base URL: `https://<saiku-host>/rest/scim/v2`
  (Jersey is prefix-mounted at `/rest/*`, the same mount as `/rest/saiku/api/*`.)
- Auth: `Authorization: Bearer <token>` — SCIM has its own token, issued by an admin.
  A Saiku username/password is **not** accepted here, and a SCIM token grants access
  to nothing but the SCIM endpoints.

## 1. Mint a SCIM token

An admin mints one token per connector (one per IdP environment is a good rule):

```bash
# Log in as an admin first; the endpoint inherits the /saiku/admin/** admin gate.
curl -X POST http://localhost:8080/rest/saiku/admin/scim/tokens \
  -H 'Content-Type: application/json' \
  -u admin:admin \
  -d '{"label":"Okta production","idp":"Okta"}'
```

The response contains `token`, the bearer secret. **It is shown once and cannot be
retrieved later** — only its SHA-256 handle is stored, so a leaked
`<saiku-home>/scim-tokens/` directory yields no usable credential. A lost secret
means minting a replacement.

List and revoke connectors:

```bash
curl -u admin:admin http://localhost:8080/rest/saiku/admin/scim/tokens
curl -X DELETE -u admin:admin \
  http://localhost:8080/rest/saiku/admin/scim/tokens/<token-handle>
```

Revocation takes effect on the connector's very next request — SCIM calls are
stateless and never mint an HTTP session.

## 2. Configure the IdP

### Okta

1. **Applications → Applications → Create App Integration → SCIM 2.0**.
2. Provisioning → Integration: set the **Single sign-on Base URL** to
   `https://<host>/rest/scim/v2` and the **Bearer token** to the minted secret.
   Okta's "Test connection" calls `GET /ServiceProviderConfig`.
3. Provisioning → **Actions**:
   - Create Users: map `userName` → `userName` (required),
     `emails[type eq "work"].value` → `email`,
     `name.givenName` / `name.familyName` → given/family name, `active` → state.
   - Deactivate Users / Reactivate Users: both map to the `active` attribute.
   - Update Users: same mapping. Deactivation and reactivation go through
     `PATCH /Users/{id}`.
4. Optional: enable **Groups** push (Group Push) to manage Saiku roles.

### Microsoft Entra ID

1. **Enterprise Applications → New Application → Create your own application →
   Integrate any other application you don't find in the gallery → SCIM**.
2. **Provision user accounts**:
   - Tenant URL: `https://<host>/rest/scim/v2`
   - Secret token: the minted secret
   - **User assignment**: "Automatically assign users" or "Assign all users".
3. Attribute mappings: Entra's defaults (`userName`, `displayName`,
   `emails[type eq "work"].value`, `active`) are all supported as-is.
   Entra sends PATCH paths as URN-qualified values
   (`urn:ietf:params:scim:schemas:core:2.0:User:active`) and operation names in
   mixed case (`"Replace"`) — both are handled.

### OneLogin

1. **Apps → Add App → SCIM Provisioning**.
2. Set the **Provisioning Base URL** to `https://<host>/rest/scim/v2` and paste the
   bearer secret as the **Bearer Token**.
3. Select the attributes to push. `userName` is required; `active` drives
   suspension; `name.givenName` / `name.familyName` are optional.

## 3. Endpoints

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/ServiceProviderConfig` | Connector validation. patch + filter supported, bulk not |
| `GET` | `/ResourceTypes`, `/Schemas`, `/Schemas/{id}` | Entra's discovery pass |
| `GET` | `/Users` | `filter` (`userName`/`id`/`displayName`/`active` with `eq`/`sw`, conjunctions with `and`), `startIndex` (1-based), `count` (default 100, max 200) |
| `GET` | `/Users/{id}` | |
| `POST` | `/Users` | Creates the account; `201` with a `Location` |
| `PUT` | `/Users/{id}` | Full replace |
| `PATCH` | `/Users/{id}` | `active`, `userName`, `name.*`, `emails[...]`, path-less value objects |
| `DELETE` | `/Users/{id}` | **Soft** delete: `active=false`, the account and its history survive |
| `GET`/`POST`/`PUT`/`PATCH`/`DELETE` | `/Groups` + `/{id}` | Group ⇒ one Saiku role, membership is authoritative |

Errors are SCIM `Error` bodies
(`urn:ietf:params:scim:api:messages:2.0:Error`) carrying `status`, `scimType`
(`uniqueness`, `invalidValue`, `invalidFilter`, `invalidPath`, `mutability`) and a
short `detail` — no SQL, class names or paths ever cross the wire.

Quick end-to-end check with curl:

```bash
SCIM=https://localhost:8080/rest/scim/v2
AUTH="Authorization: Bearer $SAIKU_SCIM_TOKEN"
CT='Content-Type: application/scim+json'

curl -H "$AUTH" "$SCIM/Users?filter=userName%20eq%20%22jsmith%22"

curl -X POST -H "$AUTH" -H "$CT" "$SCIM/Users" -d '{
  "schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
  "userName":"jsmith","active":true,
  "name":{"givenName":"Jamie","familyName":"Smith"},
  "emails":[{"value":"jamie.smith@example.com","type":"work","primary":true}]
}'

curl -X PATCH -H "$AUTH" -H "$CT" "$SCIM/Users/3" \
  -d '{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
       "Operations":[{"op":"replace","path":"active","value":false}]}'

curl -X DELETE -H "$AUTH" "$SCIM/Users/3"
```

## 4. Attribute mapping and its limits

| SCIM | Saiku | Notes |
| --- | --- | --- |
| `userName` | `USERS.USERNAME` | Canonicalised to lower case (`Usernames`), so the IdP's casing never splits one person across two ACL identities. A rename is a `mutability`-checked move, not a second account. |
| `userName` charset | — | Letters, digits and `. _ - @ +` only, ≤45 chars. Anything that could escape `/homes/<user>` is rejected with `invalidValue`. |
| `emails[type eq "work"].value` (primary preferred) | `USERS.EMAIL` | Stored as sent; not case-normalised. |
| `active` | `USERS.ENABLED` | `false` deactivates. `DELETE` is a deactivation too. |
| `name.givenName` / `name.familyName` / `displayName` | `GIVEN_NAME` / `FAMILY_NAME` / `DISPLAY_NAME` | Columns added by an idempotent `ALTER` at boot; an older database upgrades in place. |
| Groups | `USER_ROLES` | A group's `displayName` **is** the role name granted to each member. Deleting a group revokes the role everywhere first. |
| `externalId`, `enterprise:2.0:User/*` | — | **Not stored.** Saiku has no column for them, so they are accepted and dropped rather than echoed back as if saved. Filtering on `externalId` is refused with `invalidFilter` instead of quietly matching nothing. |
| `password` | — | Not a SCIM user attribute. Provisioned accounts get a random, unusable local password and authenticate through SSO. |

Two things to know about groups: Saiku's bundled `users.properties` auth profile
reads its authorities from that file, not from `USER_ROLES`, so a group
provisioned through SCIM records the role but does not by itself change what the
bundled profile will let a user do — a JDBC/LDAP `UserDetailsService` does read
`USER_ROLES`. And a bulk import (>200 users) is best done as a first
`POST /Users` pass, since `bulk` is advertised as unsupported and the surface is
rate limited.

## 5. Limits and auditing

- **100 requests/minute per token**, fixed window. Over budget the call is refused
  with `429` and a `Retry-After` header. Tune with
  `-Dsaiku.scim.rate-limit.per-minute=<n>` if a bulk import needs more headroom.
- **Every call is audit-logged** on the `org.saiku.audit` logger as one JSON line:

  ```json
  {"ts":"2026-05-04T10:15:31Z","event":"scim_call","ip":"10.0.0.9","method":"PATCH",
   "path":"/rest/scim/v2/Users/3","scim_token_label":"Okta production",
   "scim_token_id":"a1b2c3d4e5f6","scim_idp":"Okta","scim_operation":"PATCH",
   "scim_outcome":"ok","scim_status":200}
  ```

  The bearer itself is never logged. `scim_outcome` is `ok`, `rate_limited`, or the
  auth-rejection reason (`missing_bearer` / `invalid_bearer`).

## 6. Configuration

| Property / system property | Default | Meaning |
| --- | --- | --- |
| `saiku.scim.rate-limit.per-minute` | `100` | Requests per minute per token |
| `saiku.scim.default-workspace` | *(empty)* | Workspace label recorded for SCIM-provisioned accounts |
| `saiku.home` | *(launcher default)* | Where `scim-tokens/` and `scim-groups/` are persisted |

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| IdP says the base URL is invalid | It is not testing `/ServiceProviderConfig` — check the `/rest` prefix and that the bearer is the minted secret |
| 401 on every call | The token was revoked, or the IdP is sending `Basic` instead of `Bearer` |
| 409 `uniqueness` on create | The account already exists — expected on a connector re-run; treat as success |
| 400 `invalidPath` | The connector is pushing an attribute outside the documented core profile (usually `enterprise:2.0:User` or `externalId`) |
| Deactivation appears to do nothing | Look for an `active` PATCH in the audit log; `DELETE` from an IdP is recorded as a deactivation, not a row delete |

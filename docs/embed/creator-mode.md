# Embedding Saiku as a self-serve analytics surface — Creator Mode

_Applies to saiku#1435. Ships with `resourceKind: "authoring"` embed tokens
and `<saiku-embed kind="creator">`._

Everything else in the embed suite shows a **pinned resource**: a saved query, a
dashboard, an app, a cube. The host product mints the link, the visitor looks.

Creator Mode is the other half of the OEM/ISV story. Your customer signs in to
_your_ product, lands on a page you control, and **builds their own dashboard**
against a cube you pin — without ever seeing a Saiku login, a repository tree, a
datasource list, or a cube switcher. When they're done, it's saved in a folder
that belongs to them, and it can never reach another tenant's, your other
cubes, or anything else in the repository.

- 5-minute walkthrough: [`saiku-ui/src/embed/README.md`](../../saiku-ui/src/embed/README.md) → _Creator Mode_
- Quickstart for the read-only kinds: [quickstart.md](quickstart.md)

---

## The shape of it

```
your product page
  └─ <saiku-embed kind="creator" cube="foodmart/FoodMart/FoodMart/Sales" token="…">
       ├─ GET  …/embed/authoring/<cube>/context    → the pinned cube's catalogue
       ├─ POST …/embed/authoring/<cube>/preview    → Run, nothing saved
       ├─ POST …/embed/authoring/<cube>/query      → Save query
       ├─ POST …/embed/authoring/<cube>/dashboard  → Save dashboard
       └─ GET  …/embed/authoring/<cube>/objects    → "your saved items"
```

The cube comes from the token. The folder comes from the token's `tenantId`.
Neither is ever read from a request body or a URL parameter, so there is no
request a hostile host page can make that widens either one.

## 1. Pin a cube, name your tenants

Authoring tokens are **admin-only** (the same gate as `kind: "ai"` — there is no
cube-level ACL yet) and take one extra field: the tenant.

```bash
curl -X POST 'https://YOUR-SAIKU.example.com/rest/saiku/api/embed/tokens' \
  -u admin:admin \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceKind": "authoring",
    "resourcePath": "foodmart/FoodMart/FoodMart/Sales",
    "tenantId": "acme",
    "ttlHours": 24,
    "label": "Acme self-serve analytics"
  }'
# → { "status":"OK", "token":"tx-…", "tenantId":"acme", "expiresAt": … }
```

- `resourcePath` is a **cube ref** (`connection/catalog/schema/cube`), not a
  repository path.
- `tenantId` is your own customer identifier: `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`.
  It is validated at the mint — a value the server can't turn into a safe folder
  fails the request rather than being stored and discovered later.
- Mint one token **per tenant**. That is the whole point: it is what makes "a
  fresh JWT for tenant A can't read tenant B's work" true by construction. A
  short TTL (a day is a good default) plus one token per end user keeps the blast
  radius of a leaked link to one visitor's folder.
- Revoke exactly like any other token:
  `DELETE /rest/saiku/api/embed/tokens/<token>`. Takes effect on the next
  request.

Authoring tokens **cannot** be made public —
`POST /saiku/api/embed/public` with `resourceKind: "authoring"` is refused. An
anonymous write scope has no tenant to attribute it to.

## 2. Drop the creator into your page

```html
<script src="https://YOUR-SAIKU.example.com/ui/saiku-embed.js"></script>

<saiku-embed
  server="https://YOUR-SAIKU.example.com"
  token="tx-…"
  kind="creator"
  cube="foodmart/FoodMart/FoodMart/Sales"
  height="720px"
></saiku-embed>
```

In React (`@concepttocloud/saiku-embed-react`), `kind` and `cube` are typed
props, and `mintEmbedToken` takes `tenantId` for `resourceKind: "authoring"`.

What the visitor gets: the pinned cube's dimensions, levels, members and
measures; a rows picker; a measures picker; a table or bar/line/pie chart; a
**Save query** and a **Save dashboard** button; and a list of what they've
already saved. That is the entire surface.

## 3. What they can't do

| Attempted                           | Result                                                                                                                                                                                               |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Pivot to another cube               | Impossible — the cube is pinned to the token, and the URL must name the same cube. The component has no cube switcher.                                                                               |
| Read another tenant's dashboard     | Impossible — the folder is derived from the token's `tenantId`; tenant B's token resolves to a different folder inside the same owner's home.                                                        |
| Read any repository file            | Impossible — the embed identity reaches only `/rest/saiku/api/embed/authoring/**`, and no endpoint there accepts a path.                                                                             |
| Write outside their folder          | Impossible — object names are reduced to a single safe path segment server-side (`..` and separators are not preserved, and the resolved path is re-asserted to be inside the scope).                |
| Inject MDX                          | Refused. Creator queries are `QUERYMODEL` only; raw `mdx` on the query / axis / hierarchy / level, filters, sort expressions, calculated members, named sets, and query parameters are all rejected. |
| Name a member the cube doesn't have | Refused — every hierarchy, level, member and measure is checked against a frozen catalogue of the pinned cube before the MDX is generated.                                                           |
| Persist a query that doesn't run    | Refused — a query is executed once before it is written, so an object that would fail never lands in the repository.                                                                                 |
| Make the embed public               | Refused at the mint.                                                                                                                                                                                 |

The cube's own row-level security still applies: creator queries run under the
**token owner's** identity and roles (the same delegation the read surface uses),
so a customer sees what the owner would have seen — no more.

## 4. Signing with your own JWT instead

If you mint your own HS256 JWT per session (so you control the end-user identity
and never hand the browser a long-lived token), the authoring surface accepts it
with the same `SAIKU_EMBED_JWT_SECRET` the rest of the embed surface uses:

```json
{
  "sub": "user-4711",
  "saiku.resourceKind": "authoring",
  "saiku.resourcePath": "foodmart/FoodMart/FoodMart/Sales",
  "saiku.tenantId": "acme",
  "saiku.owner": "admin",
  "saiku.ownerRoles": ["ROLE_ADMIN"],
  "exp": 1780000000
}
```

`saiku.tenantId` is **required** for an authoring JWT: without it the request is
refused with the same opaque 401 as a bad signature, never a default scope. The
signature is recomputed and compared in constant time, `alg` must be exactly
`HS256`, `exp` is mandatory, and a configured audience (`SAIKU_EMBED_JWT_AUDIENCE`)
must match. The bearer of a valid JWT also gets `sub` recorded in the AI audit log
as the asserted end user.

## 5. Theming

Creator Mode reads every `--saiku-embed-*` variable the other embed kinds do
(`--saiku-embed-fg`, `-bg`, `-muted`, `-border`, `-accent`, `-error`,
`-positive`, …), plus three added for the builder chrome, all of which default to
the existing tokens so a host that sets nothing new still looks right:

| Variable                    | Used for                                |
| --------------------------- | --------------------------------------- |
| `--saiku-embed-surface`     | the builder panel background            |
| `--saiku-embed-control`     | select / text-input background          |
| `--saiku-embed-accent-soft` | hover / selected background on controls |

`theme="dark"` / `theme="auto"` swap the whole palette as they do for the read
kinds.

## 6. Operational notes

- **Where objects land.** `/homes/<owner>/embed-guest-<tenantId>/`. The folder is
  inside the token owner's home on purpose: `Acl2` isolates `/homes/<user>` per
  user, so a flat `/homes/embed-guest-<tenant>` would be unwritable for the
  identity the request actually runs as, and widening that ACL to make it work
  would hand the whole homes tree a new writer. Anchoring under the owner keeps
  the existing ACL model load-bearing. A customer's saved dashboards are visible
  to that owner in the ordinary Saiku repository tree, which is usually what an
  OEM wants anyway.
- **Per-tenant folders are created on first save**, with the standard per-file
  home ACL stamp.
- **The cube catalogue is cached 5 minutes** per cube (freezing a cube walks
  every visible level's members). A schema edit shows up within five minutes
  without a restart.
- **Very large levels are not member-filterable.** Above 1 000 members a level is
  reported as truncated, hidden from the member picker, and refused as a
  selection — the server can't vouch for a name it never enumerated, so it
  declines rather than guesses.
- **A level that fails to enumerate** is treated the same way (truncated,
  unselectable).

## 7. Endpoint reference

All under `/rest/saiku/api/embed/authoring/{connection}/{catalog}/{schema}/{cube}`.
All require `ROLE_EMBED_AUTHOR` (granted only for a valid authoring token) **or**
a fully authenticated Saiku user, so you can exercise them with `curl -u admin:admin`.

| Method   | Path                              | Body / query                     | Returns                                                                                                    |
| -------- | --------------------------------- | -------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `GET`    | `/context`                        | —                                | `tenantId`, `scopePath`, `cube`, `dimensions[]` (with `levels[]` → `members[]`, `truncated`), `measures[]` |
| `POST`   | `/preview?format=records\|matrix` | `{ "query": { …ThinQuery… } }`   | the standard query envelope (`data[]` / `matrix[]` + `metadata`)                                           |
| `POST`   | `/query`                          | `{ "name", "query": { … } }`     | `{ status, path, type }`                                                                                   |
| `POST`   | `/dashboard`                      | `{ "name", "dashboard": { … } }` | `{ status, path, type }`                                                                                   |
| `GET`    | `/objects`                        | —                                | `{ tenantId, scopePath, objects[] }`                                                                       |
| `GET`    | `/object?name=`                   | —                                | `{ name, path, content }`                                                                                  |
| `DELETE` | `/object?name=`                   | —                                | `{ status, path }`                                                                                         |

Every reply carries `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`
and `Referrer-Policy: no-referrer`, like the rest of the embed surface: the body
is rendered into a third-party page.

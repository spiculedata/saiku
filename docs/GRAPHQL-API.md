# Saiku GraphQL API

The Saiku GraphQL API exposes the same MDX + Ossie query surfaces as the REST endpoints,
with strongly-typed input types so codegen consumers (Apollo Client, `graphql-codegen`,
Relay, URQL) can generate typed clients. It's mounted at:

```
POST /rest/saiku/api/graphql
GET  /rest/saiku/api/graphql?query=…&variables=…&operationName=…
GET  /rest/saiku/api/graphql/schema.graphql
```

Auth is the same session cookie / JWT the REST endpoints use — nothing new to configure.

## Why GraphQL

Not new capability. The same cube queries already reachable via `/rest/saiku/api/ai/query`,
`/rest/saiku/api/ai/ossie/query`, MCP, and the Postgres-wire endpoint. What GraphQL adds:

- **Introspection + codegen** — point Apollo Client at `/graphql`, run
  `graphql-codegen`, get a typed React / Vue client. This is a real ergonomic win for
  embedded / ISV builds where the frontend team lives in TypeScript.
- **One-request batching** — multiple aggregations in a single round-trip.
- **The evaluator checkbox** — modern BI + embedded RFPs list GraphQL on the API row;
  missing it costs deals before feature discussion starts.

If you already use the REST or MCP endpoints and codegen isn't a priority, keep using them.
Same execution path underneath.

## Shape of the schema

The full SDL is served at `/rest/saiku/api/graphql/schema.graphql`. Top-level `Query` fields:

| Field | Purpose |
| --- | --- |
| `serverInfo` | Version + which surfaces are enabled |
| `cubes` | List every MDX cube visible to the current session |
| `cube(connectionName, catalog, schema, cubeName)` | Full `AiSchema` for one cube (JSON) |
| `ossieModels` | List every Ossie semantic model visible to the current session |
| `ossieModel(connection)` | Full `OssieModelDto` for one Ossie model (JSON) |
| `executeMdx(input: MdxQueryInput!)` | Execute an MDX query — same envelope as `POST /ai/query` (JSON) |
| `executeOssie(input: OssieQueryInput!)` | Execute an Ossie query — same envelope as `POST /ai/ossie/query` (JSON) |

Input types (`MdxQueryInput`, `OssieQueryInput`) mirror the REST body shapes field-for-field
so codegen produces the right client type and Jackson marshalling on the server needs no
special-casing. See the SDL for the canonical definitions.

Responses use the `JSON` scalar for the cube/model shapes and query results — same envelope
you know from REST. This is deliberate: keeps the schema stable when the cube estate changes
and lets you reuse the response types you already have.

## Examples

### Introspection — grab the SDL

```bash
curl -sN https://your-saiku/rest/saiku/api/graphql/schema.graphql
```

or via introspection query:

```graphql
{
  __schema {
    types { name kind }
  }
}
```

### List cubes

```graphql
{
  cubes {
    connectionName
    catalog
    schema
    cubeName
  }
}
```

### Describe a cube

```graphql
{
  cube(
    connectionName: "foodmart"
    catalog: "FoodMart"
    schema: "FoodMart"
    cubeName: "Sales"
  )
}
```

Returns the full `AiSchema` shape as JSON — measures, dimensions, hierarchies, sample
members. See [AI-QUERY-API.md](AI-QUERY-API.md) for the field semantics.

### Execute an MDX query

```graphql
query GetStoreSales($input: MdxQueryInput!) {
  executeMdx(input: $input)
}
```

With variables:

```json
{
  "input": {
    "cube": {
      "connectionName": "foodmart",
      "catalog": "FoodMart",
      "schema": "FoodMart",
      "cubeName": "Sales"
    },
    "measures": [{ "name": "Store Sales" }],
    "rows": [{ "dimension": "Product", "hierarchy": "Product", "level": "Product Family" }]
  }
}
```

Response is the `AiQueryResponse` envelope from `POST /ai/query` — records format by default.

### Execute an Ossie query

```graphql
query GetOrdersByCountry($input: OssieQueryInput!) {
  executeOssie(input: $input)
}
```

With variables:

```json
{
  "input": {
    "connection": "orders-ossie",
    "model": "semantic_model",
    "rows": [{ "dataset": "customers", "field": "customer_country" }],
    "values": [{ "metric": "total_revenue" }, { "metric": "order_count" }]
  }
}
```

## Codegen

The endpoint speaks the standard GraphQL over HTTP protocol, so any codegen tool that
understands introspection works. A typical setup with `@graphql-codegen/cli`:

```yaml
# codegen.yml
schema: "https://your-saiku/rest/saiku/api/graphql/schema.graphql"
documents: "src/**/*.graphql"
generates:
  src/generated/graphql.ts:
    plugins:
      - typescript
      - typescript-operations
      - typescript-react-apollo
```

Point Apollo Client (or URQL, Relay, etc.) at the same URL, forward the session cookie or
Authorization header the REST endpoints already accept, and every typed query in your
codebase gets client-side type checking for free.

## Errors

Standard GraphQL error envelope. Delegated REST endpoints that return non-2xx surface as
GraphQL errors with the REST body preserved under `extensions`:

```json
{
  "data": null,
  "errors": [
    {
      "message": "upstream returned HTTP 400",
      "path": ["executeMdx"],
      "extensions": {
        "status": 400,
        "body": {
          "error": "VALIDATION_ERROR",
          "field": "measures.0.name",
          "message": "measure 'Foo' not found on cube 'Sales'",
          "available": ["Store Sales", "Store Cost", "Unit Sales"]
        }
      }
    }
  ]
}
```

So client-side handling that already understands the REST `VALIDATION_ERROR` +
`available` shape works verbatim through GraphQL.

## Schema-per-cube typed access

Every cube visible to the current session is also reachable as its own typed Query field
with enum-typed measure + level inputs and a typed row output type. This is the "Cube-style"
DX — autocomplete on measure names, typed responses your codegen understands.

Naming rules (deterministic):

- **Query field** — camelCase of the cube name. Colliding cubes across catalogs get a
  schema / catalog / connection prefix appended in that order until unique.
- **Measure enum** — `{Prefix}Measure` (PascalCase of the Query field), values in
  SCREAMING_SNAKE_CASE of the canonical measure name.
- **Level enum** — `{Prefix}Level`, values as `DIMENSION__LEVEL` (double underscore).
- **Row output** — `{Prefix}Row` — every measure as a nullable `Float`, every level as a
  nullable `String`. Only the fields the query selected are populated; the rest are `null`.

Example — the FoodMart `Sales` cube:

```graphql
{
  sales(
    measures: [STORE_SALES, UNIT_SALES]
    rows: [PRODUCT__PRODUCT_FAMILY]
  ) {
    storeSales
    unitSales
    productFamily
  }
}
```

Returns:

```json
{
  "data": {
    "sales": [
      { "storeSales": 48836.21, "unitSales": 24597, "productFamily": "Drink" },
      { "storeSales": 409035.59, "unitSales": 191940, "productFamily": "Food" },
      { "storeSales": 107366.33, "unitSales": 50236, "productFamily": "Non-Consumable" }
    ]
  }
}
```

Full auto-completion on the enum values, typed response your React / Vue client can bind
directly, same numbers as the REST + XMLA + MCP surfaces.

Cubes added at runtime through the admin UI are picked up on the next call to
`POST /rest/saiku/api/graphql/refresh` (admin-only). That endpoint also wipes the persisted
query cache because the schema shape may have changed.

## Automatic Persisted Queries (APQ)

Standard Apollo APQ protocol. Bandwidth win for mobile / embedded clients where GraphQL
query text can dominate the request size.

Request shape (both POST body and GET `extensions=…` param):

```json
{
  "extensions": {
    "persistedQuery": {
      "version": 1,
      "sha256Hash": "sha256hex-of-the-query-text"
    }
  }
}
```

Flow:

1. Client sends the hash only (no `query`). Server looks it up.
2. On miss, server returns:
   ```json
   {
     "data": null,
     "errors": [
       {
         "message": "PersistedQueryNotFound",
         "extensions": { "code": "PERSISTED_QUERY_NOT_FOUND" }
       }
     ]
   }
   ```
3. Client re-sends with the full `query` text AND the hash. Server verifies
   `SHA-256(query) == sha256Hash` and stores the mapping. Subsequent hash-only calls hit.

If the hash doesn't verify on store, the server refuses with
`PERSISTED_QUERY_HASH_MISMATCH` — this prevents cache-poisoning where a client tricks the
server into storing arbitrary query text under a benign hash.

Cache defaults: 1024 entries, no expiration. Wiped automatically when the schema shape
changes (cubes added / removed on refresh). Configure per-deployment via
`saiku.graphql.apqCacheSize` / `saiku.graphql.apqExpireAfterAccessSeconds` if the defaults
don't fit your workload.

## Refresh

```
POST /rest/saiku/api/graphql/refresh
```

Admin-only. Rebuilds the GraphQL schema against the current cube estate. Wipes the
persisted query cache. Call this after `POST /admin/discover/refresh` in operator scripts.

## Limitations of the current release

- **Generic execution results are JSON scalars.** `executeMdx` / `executeOssie` return the
  same JSON envelope REST uses. The per-cube typed shape above is the ergonomic path;
  the generic surface is the escape hatch for cases you can't express typed (arbitrary
  filter JSON, mixed cubes in one query).
- **No subscriptions or mutations.** The MDX + Ossie surfaces are read-only from a
  GraphQL perspective. Cube CRUD stays on the admin REST endpoints.

## Related

- [AI-QUERY-API.md](AI-QUERY-API.md) — the REST envelope `executeMdx` returns.
- [AI-OSSIE-API.md](AI-OSSIE-API.md) — the REST envelope `executeOssie` returns.
- [MCP-SERVER-SPEC.md](MCP-SERVER-SPEC.md) — the same cubes over the MCP protocol.

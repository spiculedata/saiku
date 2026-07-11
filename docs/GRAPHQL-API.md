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

## Limitations of the initial release

- **Query results are JSON scalars.** They ride the same envelope as REST rather than
  being typed field-by-field. A follow-up (tracked internally) adds schema-first
  cube-per-Query-type generation for consumers that want full typed responses.
- **No subscriptions or mutations.** The MDX + Ossie surfaces are read-only from a
  GraphQL perspective. Cube CRUD stays on the admin REST endpoints.
- **No persisted queries or automatic persisted queries (APQ).** If your team needs
  either, tell us — we'll wire it.

## Related

- [AI-QUERY-API.md](AI-QUERY-API.md) — the REST envelope `executeMdx` returns.
- [AI-OSSIE-API.md](AI-OSSIE-API.md) — the REST envelope `executeOssie` returns.
- [MCP-SERVER-SPEC.md](MCP-SERVER-SPEC.md) — the same cubes over the MCP protocol.

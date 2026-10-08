# Ossie semantic-model YAML — operator-authored extensions

This page documents the Saiku-specific conventions layered on top of the
Ossie/OSI `semantic_model` YAML via the generic `custom_extensions[]`
field. The base `datasets` / `fields` / `metrics` / `relationships`
shape comes from `bi.saiku.ossie:ossie-core` (see the [Ossie AI Query
API](AI-OSSIE-API.md) doc for the query-facing side); everything below
is Saiku's own vendor blob, parsed by `SaikuWellKnownExtensions`.

Every well-known lives under one reserved vendor entry per object:

```yaml
custom_extensions:
- vendor_name: SAIKU
  data: '{"display": {...}, "roles": {...}, "pii": {...}}'
```

`display` and `pii` are documented in the well-known-extensions work
(saiku#1409); this page covers `roles`.

## Roles (saiku#1393 — Mondrian-role parity for Ossie)

`saiku.roles` is an allow/deny + row-predicate annotation, matched
against the caller's Spring Security `GrantedAuthority` set (the same
authorities that already drive Mondrian `<Role>` mapping — no separate
role system to administer). It can be authored on a **field**, a
**metric**, or (for `row_predicates` only) a **dataset**.

### Field / metric visibility — `allow` / `deny`

```yaml
fields:
- name: ssn
  expression: {dialects: [{dialect: ANSI_SQL, expression: SSN}]}
  custom_extensions:
  - vendor_name: SAIKU
    data: '{"roles": {"allow": ["ROLE_ADMIN"]}}'
```

- `allow` empty (or the whole `roles` block absent) = every caller
  sees the field/metric. This is the default, so existing models are
  unaffected until an operator opts in.
- A non-empty `allow` requires the caller to hold at least one of the
  listed roles.
- `deny` overrides `allow` — a role in both lists is still denied.
- A caller who is denied a field/metric:
  - never sees it in the workbench schema browser
    (`GET /discover/{connection}/ossie-model`) or the AI schema
    (`GET /ai/ossie/schema/{connection}/{model}`) — it's filtered out
    of the response entirely, not just marked hidden;
  - gets a `403`-mapped `SaikuAccessDeniedException` if a shelf state
    (`/query/execute`, `/query/preview-sql`, or the AI Query API)
    references it anyway.

Role names are matched **case-sensitively**, exactly like a Spring
`GrantedAuthority` string (`ROLE_ADMIN` ≠ `role_admin`) — document this
for operators so they don't get bitten mapping LDAP/SAML groups.

### Row-level security — `row_predicates`

Authored on a **dataset's own** `custom_extensions` (not a top-level
named-role block — see *Scope* below):

```yaml
datasets:
- name: geography
  source: DIM_GEOGRAPHY
  fields: [...]
  custom_extensions:
  - vendor_name: SAIKU
    data: '{"roles": {"row_predicates": [
        {"role": "ROLE_APAC_ANALYST", "expression": "REGION IN (''APAC'', ''Japan'')"}
      ]}}'
```

- `expression` is raw ANSI SQL — it runs inside the same `SELECT` as
  the shelf-state translator's own `WHERE` clause, so it can reference
  any column on the dataset unqualified (the dataset is always present,
  unqualified, in the emitted `FROM`).
- **Opt-in per dataset.** A dataset with no `row_predicates` entries is
  unrestricted for everyone, unchanged from before saiku#1393. A
  caller who doesn't hold *any* of a dataset's listed roles also sees
  it unrestricted — a row predicate only ever narrows access for the
  role(s) it names, it never becomes a default-deny gate. (Contrast
  with the Mondrian fail-closed fix in saiku#1968: that closed a
  different hole — a caller resolving to *no Mondrian role at all* on
  a security-*enabled* datasource. Ossie row-level security has no
  per-datasource enable flag yet; it activates per dataset the moment
  an operator authors a `row_predicates` entry naming a role, and only
  restricts callers holding a named role.)
- **Union across roles.** A caller holding two roles that both name
  predicates on the same dataset sees the predicates OR-ed together
  (`(pred_a) OR (pred_b)`) — each role opens its own access window,
  matching Mondrian's union-of-roles semantics. Predicates on
  *different* datasets are ANDed together, alongside the shelf's own
  filters.
- A predicate on the **fact dataset** (the query's anchor, always
  present in `FROM`) always fires for a matching caller — including
  for role-restricted callers whose shelf state never explicitly
  references that dataset. Keep fact-table predicates selective but
  not overly narrow; a highly selective predicate on a large fact
  table can slow every join, same caveat as an equivalent Mondrian
  `<Role>` restriction.

### Enforcement surface

| Entry point | Role check (allow/deny) | Row predicates |
| --- | --- | --- |
| `GET /discover/{connection}/ossie-model` | ✅ filtered | — (discover doesn't run SQL) |
| `GET /ai/ossie/schema/{connection}/{model}` | ✅ filtered | — |
| `POST /query/execute` (queryType `OSSIE`) | ✅ 403 on denied reference | ✅ injected |
| `POST /query/preview-sql` (queryType `OSSIE`) | ✅ 403 on denied reference | ✅ injected |
| Other `/ai/ossie/*` endpoints (anomaly, forecast, ask, values-search, …) | not yet wired | not yet wired |
| PG-wire (`saiku-sql serve`) | out of scope — admin surface, own auth story | out of scope |

### Scope and follow-ups

This first pass covers the load-bearing case: per-field/metric HIDE and
per-dataset row-level security, both keyed off the caller's existing
Spring authorities, using the `custom_extensions` escape hatch so no
change to the `ossie-core` YAML schema itself was needed. Deferred to
follow-up phases of saiku#1393:

- **Column `MASK`** (rewrite a field's SELECT expression instead of
  hiding it outright) and **metric-expression mask rewriting**.
- **Dataset-level `HIDE`** (removing a whole dataset from discover and
  refusing any shelf state that names it).
- A first-class, top-level `roles:` block naming reusable roles once
  (rather than repeating role names across every field/dataset) — this
  needs a schema addition in `ossie-core` itself.
- Wiring role enforcement through the remaining `/ai/ossie/*` endpoints
  (anomaly, forecast, ask, values-search, execute-async) and the
  PG-wire surface.
- An intersection-mode config switch (all-roles-must-agree, instead of
  today's union-of-roles).

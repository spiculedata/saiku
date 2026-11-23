# Mondrian role security

Saiku maps the Spring roles a user signs in with onto the Mondrian roles declared as
`<Role>` elements in a datasource's schema. Mondrian roles restrict what a user can see:
cubes, dimensions, hierarchies (`NONE` / `CUSTOM` / `ALL`), members, and measures. Saiku only
decides *which* Mondrian role(s) a request runs as.

## Datasource configuration

Role security is configured per datasource, in its advanced properties:

| Property | Meaning |
|---|---|
| `security.enabled` | `true` turns role security on. |
| `security.type` | `one2one`: a Spring role grants the schema role with the same name.<br>`lookup`: the explicit `security.mapping` table.<br>`passthrough`: the user's credentials go to the warehouse; no Mondrian role is applied. |
| `security.mapping` | For `lookup`: `SPRING_ROLE=MondrianRole;SPRING_ROLE=OtherRole;...`. Repeat a Spring role to grant it several Mondrian roles. |

When a user's roles resolve to several Mondrian roles, Mondrian combines them into a union
role. A non-admin whose roles resolve to **no** Mondrian role is refused (saiku#1968). An
admin whose roles resolve to none runs as Mondrian root, with full access. If a resolved role
can't be applied because the schema doesn't declare it (a typo in `security.mapping`, or a role
renamed in the schema), the connection is refused for everyone, admins included, rather than
falling back to root.

## Admin API

All endpoints need `ROLE_ADMIN`, and all paths are relative to `/rest/saiku/admin/roles`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | Inventory: every known Spring role, with its holders in the Saiku user store and its grants on each datasource, plus each datasource's security mode, the Mondrian roles its schema declares, and its mapping. |
| `POST` | `/preview` | "Test as". The body is `{"username": "alice"}` or `{"roles": ["ROLE_SALES"]}`. For each datasource it returns the access outcome (`UNSECURED`, `SCOPED`, `FULL_ADMIN`, `DENIED`, `PASSTHROUGH`, `UNKNOWN`) and the Mondrian roles the request would run as. |
| `PUT` | `/{springRole}/grants/{datasource}` | Replace the Spring role's Mondrian roles on a `lookup`-mode datasource. The body is `{"mondrianRoles": [...]}`, and an empty list revokes. It returns `409` if the datasource isn't in lookup mode, `400` (listing `available`) for a Mondrian role the schema doesn't declare, and `409` `ROLES_UNVERIFIABLE` if the schema's roles can't be read to check the grant. Revoking needs no check. |
| `DELETE` | `/{springRole}/grants/{datasource}` | Revoke all of the Spring role's grants on the datasource. |

A grant change is written to the datasource's `.sds` file and takes effect on the next
request. It also bumps the datasource's cube-metadata epoch, so cached cellsets computed under
the old grants aren't served.

The preview and enforcement (`SecurityAwareConnectionManager.applySecurity`) call the same
code, `org.saiku.service.util.security.MondrianRolePolicy`.

## Not covered yet

- Authoring the Mondrian `<Role>` definitions themselves (cube, hierarchy and member grants)
  from the UI. Edit them in the schema for now.
- Binding embed JWT claims to roles (saiku#1104).
- Auditing denied attempts beyond the existing `saiku#1968` WARN log line.

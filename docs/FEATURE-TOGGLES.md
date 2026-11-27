# Feature toggles

saiku#2618 — an admin can turn the main product surfaces on or off **per role**,
from the admin panel. Admins always keep access, every change is audited, and a
denied caller is refused by the server even with a direct link.

## What is toggleable

The catalogue is fixed (`org.saiku.service.features.FeatureCatalogue`):

| Key | Surface | REST prefixes (server-enforced) |
| --- | --- | --- |
| `dashboards` | Dashboard index, editor, comments, history, images | `/saiku/api/dashboards` |
| `apps` | App Builder index and saved apps | `/saiku/api/apps` |
| `notebooks` | Notebook index, editor, cell execution | `/saiku/api/notebooks` |
| `workbench` | MDX workbench route | — (UI-enforced only¹) |
| `sql-workbench` | Read-only SQL workbench | `/saiku/sql-workbench` |

¹ The MDX workbench shares `/saiku/api/query` with the main workspace query
editor, so a path gate would deny the workspace too. It is enforced at the UI
layer (nav entry + route guard) only; the admin panel says so. Everything else
is enforced server-side.

## Grant semantics

One JSON document at `<saiku-home>/feature-toggles.json`:

```json
{
  "features": {
    "notebooks": { "roles": ["ROLE_ANALYST"] },
    "apps": { "roles": [] }
  }
}
```

Restriction-only: an **absent** feature (or an absent config file) is
**unrestricted** — every authenticated user, exactly the pre-toggle behaviour.
`"roles": []` is **admins only**. A listed grant is those roles **plus
`ROLE_ADMIN`** — the admin panel must stay reachable for an admin, so
`ROLE_ADMIN` always passes every check. The toggles can only ever narrow what
the Spring Security chain already granted; they never grant a role the caller
does not hold. The SQL workbench's static `ROLE_SQL_EXEC` gate continues to
apply on top of its toggle.

## Enforcement

`org.saiku.web.rest.features.FeatureGateFilter` — a Jersey request filter
registered from the Spring context (`featureGateFilter` bean,
`SaikuJerseyApplication`) — runs after the Spring Security chain and refuses
(`403 FEATURE_DISABLED`) any call to a feature's REST prefixes by a caller
whose authorities are not granted. Anonymous callers are left to Spring's own
gate (401). Hiding the SPA's nav entry (`session.hasFeature` from the
`features` map the session endpoint now returns) is a navigation affordance,
not the boundary.

## Audit

Every accepted admin write appends one JSON line to
`<saiku-home>/logs/feature-toggle-audit.jsonl` (`FeatureToggleAuditLog`,
disable with `-Dfeatures.audit.enabled=false`): timestamp, actor, feature,
action (`update`/`reset`), and the before/after grants (null = unrestricted).
`GET /rest/saiku/admin/features/audit` serves the most recent entries.

#!/usr/bin/env python3
"""
metricflow_to_ossie — translate a dbt MetricFlow YAML into an Ossie
(v0.2.0.dev0) YAML that Saiku can load as an Ossie datasource.

Rationale
---------
dbt Core v1.12 will consume/emit OSI documents natively, but as of
July 2026 v1.12 hasn't shipped to PyPI (1.11.12 is latest). Meanwhile
most dbt projects in the wild have MetricFlow semantic models today.

This script bridges the gap: it reads MetricFlow YAML (the shape dbt
projects have been using since dbt 1.7) and emits Ossie YAML. The
mapping is deterministic — both formats describe the same thing.

MetricFlow → Ossie mapping
--------------------------
| MetricFlow                 | Ossie                                    |
| -------------------------- | ---------------------------------------- |
| semantic_models[*].name    | semantic_model.datasets[*].name          |
| semantic_models[*].model   | dataset.source (ref('x') → x uppercased) |
| .entities[type=primary]    | dataset.primary_key                      |
| .entities[type=foreign]    | Ossie relationships (see below)          |
| .dimensions[*]             | dataset.fields[*] (label + expr copy)    |
| .measures[*]               | (not surfaced — MetricFlow uses these    |
|                            |  as building blocks for metrics)         |
| metrics[type=simple]       | metrics[*] (measure's agg + expr)        |
| metrics[type=ratio]        | metrics[*] with SUM(num)/SUM(denom) SQL  |

Relationships
-------------
MetricFlow doesn't declare joins directly — it infers them from
matching entity names across semantic models. If dataset A has a
foreign entity `customer_id` and dataset B has a primary entity
`customer_id`, MetricFlow joins A → B on that column.

This converter replays the same rule: every foreign entity gets a
relationship to whichever other semantic model exposes the same
entity as primary.

Usage
-----
    python3 metricflow_to_ossie.py orders_semantic.yml > orders.ossie.yaml

Then drop the output into your Saiku launcher's saiku-home/data/ and
register a .sds datasource pointing at the warehouse. See README.md.
"""
from __future__ import annotations

import argparse
import sys
from typing import Any

try:
    import yaml
except ImportError:
    print("This script needs pyyaml: pip install pyyaml", file=sys.stderr)
    sys.exit(2)


AGG_FUNCS = {
    "sum": "SUM",
    "count": "COUNT",
    "count_distinct": "COUNT",  # will wrap in DISTINCT below
    "avg": "AVG",
    "average": "AVG",
    "min": "MIN",
    "max": "MAX",
    "median": "MEDIAN",
    "percentile": "PERCENTILE_CONT",
}


def measure_expression(dataset_name: str, measure: dict) -> str:
    """Compose the ANSI-SQL expression a Simple metric emits.

    MetricFlow measures have shape {name, agg, expr}. `expr` defaults to
    the measure's own name — Ossie's ANSI dialect wants a fully-qualified
    aggregate call. So `agg: sum, expr: order_total` on dataset `orders`
    becomes `SUM("orders"."order_total")`.

    COUNT with `expr: 1` — MetricFlow's canonical "count of rows" idiom —
    collapses to `COUNT(*)`. COUNT DISTINCT wraps the target expression.
    """
    agg = str(measure.get("agg", "sum")).lower()
    expr = str(measure.get("expr", measure["name"]))
    sql_func = AGG_FUNCS.get(agg, "SUM")
    if agg == "count" and expr.strip() == "1":
        return "COUNT(*)"
    ref = f'"{dataset_name}"."{expr}"'
    if agg == "count_distinct":
        return f"COUNT(DISTINCT {ref})"
    return f"{sql_func}({ref})"


def build_datasets(semantic_models: list[dict]) -> list[dict]:
    """MetricFlow's semantic_models[*] → Ossie datasets[*].

    We drop measures — those become metrics later. Dimensions become
    Ossie fields; labels + expressions carry across 1:1. Primary
    entities populate the dataset's primary_key.
    """
    out = []
    for sm in semantic_models:
        name = sm["name"]
        # ref('fct_orders') → FCT_ORDERS. Fallback to the sm.name if no model.
        source = _extract_ref(sm.get("model", name))
        primary_key = []
        for e in sm.get("entities", []):
            if e.get("type") == "primary":
                primary_key.append(e.get("expr", e["name"]).upper())
        fields = []
        for d in sm.get("dimensions", []):
            f = {
                "name": d["name"],
                "expression": {
                    "dialects": [
                        {
                            "dialect": "ANSI_SQL",
                            "expression": str(d.get("expr", d["name"])).upper(),
                        }
                    ]
                },
            }
            if d.get("label"):
                f["label"] = d["label"]
            if d.get("description"):
                f["description"] = d["description"]
            fields.append(f)
        # Add primary-key columns as fields too, so agents can filter on them.
        for e in sm.get("entities", []):
            col = str(e.get("expr", e["name"])).upper()
            if any(fld["name"].upper() == col.lower().upper() for fld in fields):
                continue
            fields.append(
                {
                    "name": e["name"],
                    "expression": {
                        "dialects": [{"dialect": "ANSI_SQL", "expression": col}]
                    },
                }
            )
        ds = {
            "name": name,
            "source": source,
            "fields": fields,
        }
        if primary_key:
            ds["primary_key"] = primary_key
        if sm.get("description"):
            ds["description"] = sm["description"]
        out.append(ds)
    return out


def build_metrics(metrics: list[dict], semantic_models: list[dict]) -> list[dict]:
    """MetricFlow metrics[*] → Ossie metrics[*].

    Simple metrics reference a measure; we look the measure up in the
    semantic_models to compose the aggregate expression. Ratio metrics
    become `SUM(numerator) / NULLIF(SUM(denominator), 0)` — safe against
    the zero-denominator case.
    """
    # Build a measure lookup: measure_name → (dataset_name, measure dict)
    measure_index: dict[str, tuple[str, dict]] = {}
    for sm in semantic_models:
        for m in sm.get("measures", []):
            measure_index[m["name"]] = (sm["name"], m)

    # And a metric lookup for ratio-composition.
    metric_index: dict[str, dict] = {m["name"]: m for m in metrics}
    out = []
    for m in metrics:
        mtype = m.get("type", "simple")
        expr = None
        agg_kind = None
        if mtype == "simple":
            measure_name = m.get("type_params", {}).get("measure")
            if not measure_name or measure_name not in measure_index:
                print(
                    f"warning: metric '{m['name']}' references unknown measure "
                    f"'{measure_name}'; skipping",
                    file=sys.stderr,
                )
                continue
            ds_name, measure = measure_index[measure_name]
            expr = measure_expression(ds_name, measure)
            agg_kind = str(measure.get("agg", "sum")).lower()
        elif mtype == "ratio":
            tp = m.get("type_params", {})
            num_name = tp.get("numerator")
            denom_name = tp.get("denominator")
            num_expr = _resolve_metric_expr(num_name, metric_index, measure_index)
            denom_expr = _resolve_metric_expr(denom_name, metric_index, measure_index)
            if num_expr and denom_expr:
                expr = f"({num_expr}) / NULLIF({denom_expr}, 0)"
                agg_kind = "ratio"
            else:
                print(
                    f"warning: ratio metric '{m['name']}' unresolvable; skipping",
                    file=sys.stderr,
                )
                continue
        else:
            # cumulative, derived — out of scope for the R1 converter.
            print(
                f"warning: metric type '{mtype}' on '{m['name']}' not translated; "
                "skipping (file an issue if you need it)",
                file=sys.stderr,
            )
            continue

        ossie_m = {
            "name": m["name"],
            "expression": {"dialects": [{"dialect": "ANSI_SQL", "expression": expr}]},
        }
        if agg_kind:
            ossie_m["aggregation_kind"] = agg_kind
        if m.get("description"):
            ossie_m["description"] = m["description"]
        out.append(ossie_m)
    return out


def build_relationships(semantic_models: list[dict]) -> list[dict]:
    """Infer relationships from matching entity names across semantic models.

    MetricFlow's join semantics: dataset A's `type: foreign` entity joins
    to dataset B's `type: primary` entity when the entity names match.
    We replay that here, producing Ossie relationships that the auto-join
    rule can act on at query time.
    """
    # Index each primary entity: name → (dataset_name, column_expr)
    primaries: dict[str, tuple[str, str]] = {}
    for sm in semantic_models:
        for e in sm.get("entities", []):
            if e.get("type") == "primary":
                primaries[e["name"]] = (
                    sm["name"],
                    str(e.get("expr", e["name"])).upper(),
                )
    rels = []
    seen = set()
    for sm in semantic_models:
        for e in sm.get("entities", []):
            if e.get("type") != "foreign":
                continue
            if e["name"] not in primaries:
                continue
            src_col = str(e.get("expr", e["name"])).upper()
            to_ds, to_col = primaries[e["name"]]
            rel_name = f"{sm['name']}_to_{to_ds}"
            key = (sm["name"], to_ds, src_col, to_col)
            if key in seen:
                continue
            seen.add(key)
            rels.append(
                {
                    "name": rel_name,
                    "from": sm["name"],
                    "to": to_ds,
                    "from_columns": [src_col],
                    "to_columns": [to_col],
                }
            )
    return rels


def convert(source: dict, model_name: str, description: str | None = None) -> dict:
    """Assemble a full Ossie document from the parsed MetricFlow YAML."""
    semantic_models = source.get("semantic_models", [])
    metrics = source.get("metrics", [])
    ossie_model: dict[str, Any] = {"name": model_name}
    if description:
        ossie_model["description"] = description
    ossie_model["datasets"] = build_datasets(semantic_models)
    metric_defs = build_metrics(metrics, semantic_models)
    if metric_defs:
        ossie_model["metrics"] = metric_defs
    rels = build_relationships(semantic_models)
    if rels:
        ossie_model["relationships"] = rels
    return {"version": "0.2.0.dev0", "semantic_model": [ossie_model]}


def _extract_ref(model_expr: str) -> str:
    """`ref('fct_orders')` → `FCT_ORDERS`. Bare strings pass through."""
    s = model_expr.strip()
    if s.startswith("ref(") and s.endswith(")"):
        inner = s[4:-1].strip().strip("'\"")
        return inner.upper()
    return s.upper()


def _resolve_metric_expr(
    name: str | None,
    metric_index: dict[str, dict],
    measure_index: dict[str, tuple[str, dict]],
) -> str | None:
    """Resolve a metric-name reference (from ratio numerator/denominator)
    to a concrete SQL expression by walking through the metric+measure
    indices. One level deep only — nested ratios need iteration."""
    if not name:
        return None
    if name in metric_index:
        m = metric_index[name]
        if m.get("type") == "simple":
            measure_name = m.get("type_params", {}).get("measure")
            if measure_name in measure_index:
                ds_name, measure = measure_index[measure_name]
                return measure_expression(ds_name, measure)
    if name in measure_index:
        ds_name, measure = measure_index[name]
        return measure_expression(ds_name, measure)
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("source", help="Path to MetricFlow YAML.")
    ap.add_argument(
        "--model-name",
        default="Model",
        help="Name to give the emitted Ossie semantic_model. Default: Model.",
    )
    ap.add_argument(
        "--description",
        default=None,
        help="Optional description written to semantic_model.description.",
    )
    args = ap.parse_args()
    with open(args.source) as f:
        src = yaml.safe_load(f)
    out = convert(src, args.model_name, args.description)
    yaml.safe_dump(out, sys.stdout, sort_keys=False, default_flow_style=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())

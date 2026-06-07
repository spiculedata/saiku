# Synthetic Looker model for the Bank demo — demonstrates Looker -> M4 migration.
# Over the same mm_* tables as demo/bank.sql + demo/Bank.mondrian.xml.
#
# Covered import paths:
#   many-to-many bridge two-hop  -> <BridgeLink>            (#124)
#   access_filter on a column    -> <PredicateGrant>        (#106)
#   access_filter on a dim key   -> <HierarchyGrant>        (#115)
#   sum_distinct (same-view key) -> measure-level distinct grain (#117/#119)
#   type: tier                   -> <Tier>                  (#108)
#   dimension_group type:duration-> <Duration>             (#108)
#   type: median / percentile    -> median/percentile measures (#104)
#
# Transpile with the schema CLI / LookML importer; see demo/lookml/README.md.

view: account_fact {
  sql_table_name: mm_fact ;;
  dimension: account_id {
    type: number
    primary_key: yes
    sql: ${TABLE}.account_id ;;
  }
  # type: tier -> native <Tier> binning over the balance column.
  dimension: balance_tier {
    type: tier
    tiers: [1000, 3000]
    sql: ${TABLE}.balance ;;
  }
  # type: duration -> native <Duration> (account age in years).
  dimension_group: account_age {
    type: duration
    intervals: [year]
    sql_start: ${TABLE}.open_date ;;
    sql_end: ${TABLE}.as_of_date ;;
  }
  measure: balance { type: sum sql: ${TABLE}.balance ;; }
  measure: median_balance { type: median sql: ${TABLE}.balance ;; }
  measure: p90_balance {
    type: percentile
    percentile: 90
    sql: ${TABLE}.balance ;;
  }
  # NOTE: `tenant` is intentionally NOT declared as a dimension, so the
  # access_filter below maps to a PredicateGrant (arbitrary fact column),
  # not a member/hierarchy grant.
}

view: account_owner {
  sql_table_name: mm_owner ;;
  dimension: account_id { type: number sql: ${TABLE}.account_id ;; }
  dimension: customer_id { sql: ${TABLE}.customer_id ;; }
}

view: customer {
  sql_table_name: mm_customer ;;
  dimension: customer_id {
    primary_key: yes
    sql: ${TABLE}.customer_id ;;
  }
  # A modelled dimension key -> access_filter on it becomes a HierarchyGrant.
  dimension: segment { type: string sql: ${TABLE}.segment ;; }
}

view: txn {
  sql_table_name: mm_txn ;;
  dimension: txn_id { type: number sql: ${TABLE}.txn_id ;; }
  # sum_distinct on a same-view non-primary-key column -> measure-level
  # distinct grain (#119): de-dups on txn_id before summing.
  measure: distinct_amount {
    type: sum_distinct
    sql_distinct_key: ${TABLE}.txn_id ;;
    sql: ${TABLE}.amount ;;
  }
}

# The bridge two-hop: fact -> owners (one_to_many) -> customer (many_to_one).
explore: account_fact {
  join: account_owner {
    type: left_outer
    relationship: one_to_many
    sql_on: ${account_fact.account_id} = ${account_owner.account_id} ;;
  }
  join: customer {
    type: left_outer
    relationship: many_to_one
    sql_on: ${account_owner.customer_id} = ${customer.customer_id} ;;
  }
  # Predicate row-security on an arbitrary fact column, bound to a user attribute.
  access_filter: { field: account_fact.tenant user_attribute: tenant }
  # Member-level row-security on a modelled dimension key.
  access_filter: { field: customer.segment user_attribute: segment }
}

explore: txn {}

---
name: weekly-foodmart-rollup
description: |
  Weekly revenue rollup for the FoodMart Sales cube: total Store Sales
  by Product Family for the last 7 days, compared to the prior 7 days.
  Flags any family with a >20% swing.
cube: unknown_foodmart/FoodMart/FoodMart/Sales
---

## Steps

1. Query total `[Measures].[Store Sales]` and `[Measures].[Unit Sales]`
   broken down by `[Product].[Product Family]` for the last 7 days
   (`[Time].[Weekly].[Week].&[latest]`).

2. Query the same shape for the prior 7 days
   (`[Time].[Weekly].[Week].&[latest - 1]`).

3. Present the result as a two-column table with a `Δ vs prior` percentage
   column derived per family.

4. Highlight any Product Family whose `Δ vs prior` swings by more than 20%
   in either direction — flag it inline and include a one-sentence note
   on which measure moved most (Store Sales vs Unit Sales).

5. If Store Sales moved but Unit Sales stayed flat, note "price effect" in
   the flag. If both moved together, note "volume effect".

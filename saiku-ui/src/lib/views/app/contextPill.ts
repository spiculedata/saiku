/*
 * Pure logic for the header context selector (the "STORE / Portland #14 ▾"
 * control).
 *
 * The pill used to be a static <button> with a title attribute — it looked like
 * the reference's store switcher but did nothing. This turns it into a real
 * filter control while keeping the decision-making out of the component: what
 * the options are, which one is selected, and what filter (if any) a selection
 * should produce.
 *
 * Member resolution has three cases, in order:
 *   member === "*"        -> ALL: clear the filter
 *   member set            -> use it verbatim (an MDX unique name)
 *   member blank          -> resolve `label` as a member CAPTION, async, via
 *                            the caller's lookup (see AppShell)
 */

import type { AppContextPill, AppContextPillOption } from "$lib/api/apps";
import type { DashboardFilter } from "$lib/api/dashboards";

/** Sentinel meaning "no filter" — an "All stores" entry. */
export const ALL_MEMBER = "*";

/** Does this pill actually offer a choice? */
export function isSelectable(pill: AppContextPill | undefined): boolean {
  return !!pill && Array.isArray(pill.options) && pill.options.length > 0;
}

/** The options to render. Always includes the pill's current `value` as the
 *  leading entry when the author's list doesn't already contain it, so the
 *  displayed value is never absent from its own selector. */
export function optionsFor(pill: AppContextPill | undefined): AppContextPillOption[] {
  if (!pill) return [];
  const options = pill.options ?? [];
  if (options.length === 0) return [];
  return options.some((o) => o.label === pill.value)
    ? options
    : [{ label: pill.value, member: ALL_MEMBER }, ...options];
}

/** The label the pill should display: the live selection when it's still one of
 *  the pill's options, otherwise the configured default. Validating rather than
 *  trusting `selected` means a selection left over from another app — or from
 *  options the author has since edited — falls back instead of displaying a
 *  value that no longer exists. */
export function effectiveLabel(
  pill: AppContextPill | undefined,
  selected: string | null | undefined,
): string {
  if (selected && optionsFor(pill).some((o) => o.label === selected)) return selected;
  return pill?.value ?? "";
}

/** Look up an option by its label. */
export function optionByLabel(
  pill: AppContextPill | undefined,
  label: string,
): AppContextPillOption | undefined {
  return optionsFor(pill).find((o) => o.label === label);
}

/** What selecting `option` should do to the filter set.
 *
 *  - `kind: "none"`   — the pill isn't bound to a level; only the label changes.
 *  - `kind: "clear"`  — an ALL entry; drop the filter.
 *  - `kind: "set"`    — filter on `filter.members`.
 *  - `kind: "resolve"`— the author gave a caption, not a unique name; the
 *                       caller resolves it against the level then applies it.
 */
export type ContextSelection =
  | { kind: "none" }
  | { kind: "clear"; target: { dimension: string; hierarchy: string; level: string } }
  | { kind: "set"; filter: DashboardFilter }
  | {
      kind: "resolve";
      caption: string;
      target: { dimension: string; hierarchy: string; level: string };
    };

export function selectionFor(
  pill: AppContextPill | undefined,
  option: AppContextPillOption | undefined,
): ContextSelection {
  if (!pill || !option) return { kind: "none" };
  const target = pill.filter;
  if (!target || !target.dimension || !target.hierarchy || !target.level) return { kind: "none" };

  const member = (option.member ?? "").trim();
  if (member === ALL_MEMBER) return { kind: "clear", target };
  if (member) {
    return { kind: "set", filter: { ...target, members: [member] } };
  }
  const caption = option.label.trim();
  if (!caption) return { kind: "clear", target };
  return { kind: "resolve", caption, target };
}


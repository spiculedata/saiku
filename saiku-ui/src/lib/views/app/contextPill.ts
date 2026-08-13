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

/** Upper bound on options built from a cube level. A native select with
 *  thousands of entries is unusable, and the fetch itself gets expensive —
 *  above this the pill is the wrong control for the job. Truncation is
 *  reported (see {@link levelOptionsTruncated}), never silent. */
export const MAX_LEVEL_OPTIONS = 200;

/** Does the pill read its options from the cube rather than a typed list? */
export function isLevelSourced(pill: AppContextPill | undefined): boolean {
  return (
    pill?.optionsSource === "level" &&
    !!pill.filter?.dimension &&
    !!pill.filter?.hierarchy &&
    !!pill.filter?.level
  );
}

/** One member as returned by the members endpoint. */
export interface LevelMember {
  uniqueName: string;
  caption: string;
}

/**
 * Build selector options from a cube level's members.
 *
 * Uses each member's real `uniqueName`, so nothing has to be resolved by
 * caption later and a renamed member can't silently stop matching. An "All"
 * entry is prepended when the pill asks for one.
 */
export function optionsFromMembers(
  pill: AppContextPill | undefined,
  members: ReadonlyArray<LevelMember>,
): AppContextPillOption[] {
  if (!pill) return [];
  const capped = members
    .slice(0, MAX_LEVEL_OPTIONS)
    .filter((m) => m.caption?.trim() && m.uniqueName?.trim())
    .map((m) => ({ label: m.caption, member: m.uniqueName }));
  if (pill.includeAll) {
    return [{ label: pill.allLabel?.trim() || "All", member: ALL_MEMBER }, ...capped];
  }
  return capped;
}

/** True when a level's member list was longer than the pill can show. Callers
 *  surface this rather than quietly presenting a partial list as complete. */
export function levelOptionsTruncated(memberCount: number): boolean {
  return memberCount > MAX_LEVEL_OPTIONS;
}

/** Does this pill actually offer a choice?
 *
 *  `resolved` carries the cube-sourced options once they've loaded; a
 *  level-sourced pill is not selectable until then, so it renders as static
 *  text rather than briefly offering an empty dropdown. */
export function isSelectable(
  pill: AppContextPill | undefined,
  resolved?: ReadonlyArray<AppContextPillOption>,
): boolean {
  if (!pill) return false;
  if (isLevelSourced(pill)) return (resolved?.length ?? 0) > 0;
  return Array.isArray(pill.options) && pill.options.length > 0;
}

/** The options to render. Always includes the pill's current `value` as the
 *  leading entry when the list doesn't already contain it, so the displayed
 *  value is never absent from its own selector. */
export function optionsFor(
  pill: AppContextPill | undefined,
  resolved?: ReadonlyArray<AppContextPillOption>,
): AppContextPillOption[] {
  if (!pill) return [];
  if (isLevelSourced(pill)) {
    // The cube's members ARE the options. A configured default that doesn't
    // match one is an authoring mistake, and synthesising an entry for it would
    // quietly behave as "All" — a row that says "Portland #14" and silently
    // clears the filter. effectiveLabel falls back to a real option instead.
    return [...(resolved ?? [])];
  }
  const options = pill.options ?? [];
  if (options.length === 0) return [];
  return options.some((o) => o.label === pill.value)
    ? [...options]
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
  resolved?: ReadonlyArray<AppContextPillOption>,
): string {
  const options = optionsFor(pill, resolved);
  if (selected && options.some((o) => o.label === selected)) return selected;
  // A cube-sourced pill can only display something the cube actually offers.
  // Falling back to the first option (the "All" entry when there is one) is
  // honest about the initial state: nothing is filtered yet.
  if (isLevelSourced(pill) && options.length > 0) {
    return options.some((o) => o.label === pill?.value) ? pill!.value : options[0].label;
  }
  return pill?.value ?? "";
}

/** Look up an option by its label. */
export function optionByLabel(
  pill: AppContextPill | undefined,
  label: string,
  resolved?: ReadonlyArray<AppContextPillOption>,
): AppContextPillOption | undefined {
  return optionsFor(pill, resolved).find((o) => o.label === label);
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


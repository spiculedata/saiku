/*
 * Page-icon vocabulary for App Builder navigation (saiku#1805).
 *
 * `AppPage.icon` is a NAME, not a component reference — the .saikuapp document
 * has to stay plain JSON, so an author's choice is only as good as the names
 * this module knows. Anything unrecognised falls back to the generic dashboard
 * glyph, silently, which is why the vocabulary needs to cover the pages a BI
 * app actually has.
 *
 * It previously carried eight glyphs (home, chart, trend, cube, users,
 * settings, sparkles, table) behind eleven keys. None of them fit a geography
 * page, a site/estate page, a time page or a finance page — building an estate
 * app meant putting `chart` on a page of maps and `table` on a page about floor
 * space, which is worse than no icon at all.
 *
 * The rail and the inspector's picker BOTH read from here. They used to keep
 * separate hard-coded lists, and had already drifted: the picker omitted
 * `house`, so a key the renderer honoured could not be chosen.
 */

import {
  LayoutDashboard,
  House,
  ChartColumnBig,
  Boxes,
  Users,
  Settings,
  Sparkles,
  TrendingUp,
  Table,
  Map,
  Globe,
  MapPin,
  Building2,
  Store,
  Warehouse,
  Truck,
  Calendar,
  Clock,
  Banknote,
  Receipt,
  TriangleAlert,
  Search,
  Layers,
  Flag,
  Target,
  Package,
} from "@lucide/svelte";

export type PageIcon = typeof LayoutDashboard;

/** The glyph used when a page names no icon, or names one we don't know. */
export const DEFAULT_PAGE_ICON: PageIcon = LayoutDashboard;

/**
 * The canonical vocabulary, in picker order — roughly "where you are" →
 * "what it looks like" → "what it's about".
 *
 * Order is deliberate: the picker renders this list as a grid, so it doubles as
 * the layout.
 */
export const PAGE_ICON_KEYS = [
  "home",
  "chart",
  "trend",
  "table",
  "layers",
  "map",
  "globe",
  "pin",
  "building",
  "store",
  "warehouse",
  "truck",
  "boxes",
  "package",
  "users",
  "calendar",
  "clock",
  "money",
  "receipt",
  "target",
  "flag",
  "alert",
  "search",
  "sparkles",
  "settings",
] as const;

export type PageIconKey = (typeof PAGE_ICON_KEYS)[number];

/**
 * Name → glyph. Includes the aliases apps already in the field may carry
 * (`house`, `cube`, `people`) — those are NOT offered in the picker, but must
 * keep resolving or a saved app would lose its icons.
 */
export const PAGE_ICONS: Record<string, PageIcon> = {
  home: House,
  chart: ChartColumnBig,
  trend: TrendingUp,
  table: Table,
  layers: Layers,
  map: Map,
  globe: Globe,
  pin: MapPin,
  building: Building2,
  store: Store,
  warehouse: Warehouse,
  truck: Truck,
  boxes: Boxes,
  package: Package,
  users: Users,
  calendar: Calendar,
  clock: Clock,
  money: Banknote,
  receipt: Receipt,
  target: Target,
  flag: Flag,
  alert: TriangleAlert,
  search: Search,
  sparkles: Sparkles,
  settings: Settings,

  /* --- back-compat aliases (resolve, but not offered) ------------------- */
  house: House,
  cube: Boxes,
  people: Users,
};

/** Resolve a page's icon name to a glyph, falling back to the generic one. */
export function pageIcon(name: string | undefined | null): PageIcon {
  return (name && PAGE_ICONS[name]) || DEFAULT_PAGE_ICON;
}

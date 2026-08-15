/*
 * Date-range filter expansion (saiku#925).
 *
 * A `date-range` widget on the filter panel surfaces two HTML date
 * inputs (from, to) but the underlying cube doesn't speak dates — it
 * speaks members at some time level (Year, Quarter, Month, Day). This
 * module parses each candidate member's MDX unique name into a
 * concrete `[start, end]` calendar range and returns the subset whose
 * range intersects the user's chosen window.
 *
 * Heuristics — works for the common Mondrian time hierarchies
 * (Year → Quarter → Month → Day or Year → Month → Day), tolerates
 * both numeric ("1") and English-named ("January") month captions,
 * and gracefully returns no expansion for members the parser can't
 * place on the calendar (cube-specific weird hierarchies, fiscal
 * calendars with non-standard captions).
 *
 * The output is the list of MDX unique names to feed into the panel
 * filter's `members[]` — the rest of the query pipeline already
 * accepts a flat list at the configured level, so the slicer fires
 * with no further server changes.
 */

const MONTHS_FULL = [
	'january',
	'february',
	'march',
	'april',
	'may',
	'june',
	'july',
	'august',
	'september',
	'october',
	'november',
	'december'
];

const MONTHS_SHORT = [
	'jan',
	'feb',
	'mar',
	'apr',
	'may',
	'jun',
	'jul',
	'aug',
	'sep',
	'oct',
	'nov',
	'dec'
];

export interface MemberRange {
	start: Date;
	end: Date;
}

/** Parse a Mondrian unique name like `[Time].[Time].[1997].[Q1]` into
 *  the calendar range the member covers. Returns null when the parser
 *  can't place the member confidently (no recognisable year segment,
 *  unknown captions). */
export function parseMemberDateRange(uniqueName: string): MemberRange | null {
	const segs = uniqueName.match(/\[[^\]]+\]/g);
	if (!segs) return null;
	// Skip the [Dim].[Hier] prefix segments; the rest are level values
	// in declaration order (root-most first).
	const levelSegs = segs.slice(2).map((s) => s.slice(1, -1));
	if (levelSegs.length === 0) return null;

	let year: number | null = null;
	let monthStart = 0; // 0-indexed (January = 0)
	let monthEnd = 11;
	let dayStart = 1;
	let dayEnd: number | null = null; // null = clamp to end of month

	let haveQuarter = false;
	let haveMonth = false;

	for (const raw of levelSegs) {
		const v = raw.trim();
		// Year — 4 digit number.
		if (year == null && /^\d{4}$/.test(v)) {
			year = parseInt(v, 10);
			continue;
		}
		// Quarter — "Q1" .. "Q4" (case-insensitive).
		const qm = v.match(/^Q([1-4])$/i);
		if (qm) {
			const q = parseInt(qm[1], 10);
			monthStart = (q - 1) * 3;
			monthEnd = (q - 1) * 3 + 2;
			haveQuarter = true;
			continue;
		}
		// Month — numeric 1-12 OR English name.
		if (!haveMonth) {
			const n = parseInt(v, 10);
			if (!isNaN(n) && n >= 1 && n <= 12 && /^\d{1,2}$/.test(v)) {
				monthStart = n - 1;
				monthEnd = n - 1;
				haveMonth = true;
				continue;
			}
			const lower = v.toLowerCase();
			const fi = MONTHS_FULL.indexOf(lower);
			if (fi >= 0) {
				monthStart = fi;
				monthEnd = fi;
				haveMonth = true;
				continue;
			}
			const si = MONTHS_SHORT.indexOf(lower);
			if (si >= 0) {
				monthStart = si;
				monthEnd = si;
				haveMonth = true;
				continue;
			}
		}
		// Day — numeric 1-31, only meaningful after a month is known.
		if (haveMonth && /^\d{1,2}$/.test(v)) {
			const d = parseInt(v, 10);
			if (d >= 1 && d <= 31) {
				dayStart = d;
				dayEnd = d;
			}
		}
	}

	// Need at least the year to place the member on the calendar.
	if (year == null) return null;

	// Quarter parsed but no narrower month → start/end span the quarter;
	// year-only members span the whole year (monthStart/monthEnd already 0/11).
	void haveQuarter;
	const start = new Date(year, monthStart, dayStart);
	const endMonth = monthEnd;
	const endDay = dayEnd ?? lastDayOfMonth(year, endMonth);
	const end = new Date(year, endMonth, endDay, 23, 59, 59, 999);
	return { start, end };
}

function lastDayOfMonth(year: number, monthZeroIndexed: number): number {
	// Day 0 of the next month is the last day of the current month.
	return new Date(year, monthZeroIndexed + 1, 0).getDate();
}

/** Given a date-range picker's [from, to] window and the catalogue of
 *  members at the filter's level, return the unique names whose
 *  calendar range intersects the window. Members that can't be parsed
 *  are silently skipped. Empty from / to → empty result. */
export function expandDateRange(
	from: Date | null,
	to: Date | null,
	members: { uniqueName: string; caption: string }[]
): string[] {
	if (!from || !to) return [];
	// Normalise to inclusive day bounds.
	const winStart = new Date(from.getFullYear(), from.getMonth(), from.getDate(), 0, 0, 0, 0);
	const winEnd = new Date(to.getFullYear(), to.getMonth(), to.getDate(), 23, 59, 59, 999);
	const out: string[] = [];
	for (const m of members) {
		const range = parseMemberDateRange(m.uniqueName);
		if (!range) continue;
		if (range.start <= winEnd && range.end >= winStart) {
			out.push(m.uniqueName);
		}
	}
	return out;
}

/** Format a Date as `YYYY-MM-DD` for binding to `<input type="date">`.
 *  Returns "" for null so the input renders empty. */
export function toDateInputValue(d: Date | null): string {
	if (!d) return '';
	const yyyy = d.getFullYear().toString().padStart(4, '0');
	const mm = (d.getMonth() + 1).toString().padStart(2, '0');
	const dd = d.getDate().toString().padStart(2, '0');
	return `${yyyy}-${mm}-${dd}`;
}

/** Parse `YYYY-MM-DD` (as emitted by `<input type="date">`) into a
 *  local-time Date at midnight. Returns null for empty strings or
 *  malformed input so callers can short-circuit cleanly. */
export function fromDateInputValue(s: string): Date | null {
	const m = s.match(/^(\d{4})-(\d{2})-(\d{2})$/);
	if (!m) return null;
	const y = parseInt(m[1], 10);
	const mo = parseInt(m[2], 10);
	const d = parseInt(m[3], 10);
	if (mo < 1 || mo > 12 || d < 1 || d > 31) return null;
	return new Date(y, mo - 1, d);
}

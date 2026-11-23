/*
 * Presentation helpers for the Roles admin tab (saiku#779). Pure so they can be unit-tested
 * without mounting the view.
 */
import type { RoleAccess, RoleSecurityMode } from '$lib/api/admin';

type BadgeTone = 'success' | 'error' | 'warning' | 'info' | 'neutral';

const ACCESS: Record<RoleAccess, { label: string; tone: BadgeTone; hint: string }> = {
	UNSECURED: {
		label: 'Unsecured',
		tone: 'warning',
		hint: 'Role security is off for this datasource: everyone sees everything.'
	},
	SCOPED: {
		label: 'Scoped',
		tone: 'success',
		hint: 'Queries run as the listed Mondrian role(s).'
	},
	FULL_ADMIN: {
		label: 'Full (admin)',
		tone: 'info',
		hint: 'No role resolved, but an admin gets Mondrian root: full access.'
	},
	DENIED: {
		label: 'Denied',
		tone: 'error',
		hint: 'No role resolved and not an admin: the connection is refused.'
	},
	PASSTHROUGH: {
		label: 'Pass-through',
		tone: 'neutral',
		hint: "The user's own credentials are sent to the warehouse, which decides access."
	},
	UNKNOWN: {
		label: 'Misconfigured',
		tone: 'warning',
		hint: 'Security is enabled with an unrecognised security.type; no role is applied.'
	}
};

const MODES: Record<RoleSecurityMode, string> = {
	DISABLED: 'Off',
	ONE2ONE: 'One-to-one',
	LOOKUP: 'Lookup table',
	PASSTHROUGH: 'Pass-through',
	UNKNOWN: 'Unknown type'
};

export function accessLabel(access: RoleAccess): string {
	return ACCESS[access]?.label ?? access;
}

export function accessTone(access: RoleAccess): BadgeTone {
	return ACCESS[access]?.tone ?? 'neutral';
}

export function accessHint(access: RoleAccess): string {
	return ACCESS[access]?.hint ?? '';
}

export function modeLabel(mode: RoleSecurityMode): string {
	return MODES[mode] ?? mode;
}

/** Split a comma- or newline-separated role list, trimming and dropping blanks and duplicates. */
export function parseRoleList(text: string): string[] {
	const out: string[] = [];
	for (const part of text.split(/[,\n]/)) {
		const r = part.trim();
		if (r && !out.includes(r)) out.push(r);
	}
	return out;
}

/** Toggle `role` in `list`, preserving order. */
export function toggle(list: string[], role: string): string[] {
	return list.includes(role) ? list.filter((r) => r !== role) : [...list, role];
}

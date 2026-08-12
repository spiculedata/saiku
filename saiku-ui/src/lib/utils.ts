import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]): string {
	return twMerge(clsx(inputs));
}

/**
 * Wrap a raw member/measure name as a bracketed MDX segment, escaping embedded
 * `]` per the MDX convention (`]` → `]]`) — saiku#1286. Without the escape a
 * (schema-admin-authored) name containing `]` produces a malformed unique name
 * like `[Measures].[a]b]`; the server drops it fail-closed, so the symptom is a
 * silently missing measure rather than anything exploitable, but the reference
 * should be well-formed at the source.
 */
export function mdxSegment(name: string): string {
	return `[${name.replaceAll(']', ']]')}]`;
}

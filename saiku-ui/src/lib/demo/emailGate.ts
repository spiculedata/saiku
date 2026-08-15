/*
 * Pure helpers for the demo email gate (saiku#1029). No DOM, no fetch — the
 * Svelte component and the API module stay thin so this logic is unit-tested in
 * one place (mirrors the dashboard/* helper convention).
 */

/** Loose email check — mirrors the server's DemoGateResource regex. */
export function isValidEmail(email: string): boolean {
	return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test((email ?? '').trim());
}

/** Strip everything but digits and cap at 6 — a WorkOS Magic Auth code. */
export function normalizeCode(raw: string): string {
	return (raw ?? '').replace(/\D/g, '').slice(0, 6);
}

/** A complete, submittable 6-digit code. */
export function isCompleteCode(code: string): boolean {
	return /^\d{6}$/.test(code ?? '');
}

/** Two-step flow: collect the email, then the emailed code. */
export type GateStep = 'email' | 'code';

export interface SaikuSession {
	username: string;
	roles: string[];
	sessionid: string;
	language: string;
	isadmin: boolean;
	/** saiku#2618: the caller's effective feature map (feature key -> enabled), served by
	 *  the session endpoint. Absent (older backend) means every feature is on. */
	features?: Record<string, boolean>;
}

const REST_BASE = '/rest/saiku';

async function jsonOrNull<T>(res: Response): Promise<T | null> {
	const text = await res.text();
	if (!text) return null;
	try {
		return JSON.parse(text) as T;
	} catch {
		return null;
	}
}

export async function login(username: string, password: string): Promise<SaikuSession> {
	const body = new URLSearchParams({ username, password });
	const res = await fetch(`${REST_BASE}/session`, {
		method: 'POST',
		credentials: 'include',
		headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
		body
	});
	if (!res.ok) {
		throw new Error(`Login failed (${res.status})`);
	}
	const session = await getCurrentSession();
	if (!session) throw new Error('Login succeeded but no session was returned');
	return session;
}

export async function getCurrentSession(): Promise<SaikuSession | null> {
	const res = await fetch(`${REST_BASE}/session`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (res.status === 401 || res.status === 403 || !res.ok) return null;
	const parsed = await jsonOrNull<Partial<SaikuSession>>(res);
	if (!parsed || !parsed.username || !Array.isArray(parsed.roles)) return null;
	return parsed as SaikuSession;
}

export async function logout(): Promise<void> {
	await fetch(`${REST_BASE}/session`, { method: 'DELETE', credentials: 'include' });
}

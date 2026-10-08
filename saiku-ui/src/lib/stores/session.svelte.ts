import {
	getCurrentSession,
	login as apiLogin,
	logout as apiLogout,
	type SaikuSession
} from '$lib/api/session';
import { datasources } from '$lib/stores/datasources.svelte';
import { selection } from '$lib/stores/selection.svelte';

class SessionStore {
	current = $state<SaikuSession | null>(null);
	loading = $state<boolean>(true);

	async bootstrap(): Promise<void> {
		this.loading = true;
		try {
			this.current = await getCurrentSession();
		} finally {
			this.loading = false;
		}
	}

	async login(username: string, password: string): Promise<void> {
		const s = await apiLogin(username, password);
		this.current = s;
	}

	async logout(): Promise<void> {
		await apiLogout();
		this.current = null;
		datasources.clear();
		selection.clear();
	}

	get isAdmin(): boolean {
		return this.current?.isadmin === true;
	}

	/** True for any role in the session's `roles` array. Admins implicitly pass every check —
	 *  ROLE_ADMIN is treated as a superset of every feature-gating role (mirrors the backend's
	 *  `@RolesAllowed({"ROLE_ADMIN", ...})` convention, e.g. the SQL workbench's ROLE_SQL_EXEC). */
	hasRole(role: string): boolean {
		return this.isAdmin || this.current?.roles.includes(role) === true;
	}
}

export const session = new SessionStore();

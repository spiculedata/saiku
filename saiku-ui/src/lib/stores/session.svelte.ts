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

	/** saiku#2618: true when the caller may use a toggleable UI feature (Dashboards, Apps,
	 *  Notebooks, the MDX workbench, the SQL workbench). The backend session response carries
	 *  the caller's effective feature map — admins always pass, and a feature the admin has
	 *  denied for the caller's roles is false. An absent map (older backend, or the session
	 *  response failed to carry it) means every feature is on — the pre-#2618 posture. The
	 *  server-side FeatureGateFilter enforces the same contract on the REST surface regardless
	 *  of what the UI shows, so this is a navigation affordance, not the boundary. */
	hasFeature(feature: string): boolean {
		const features = this.current?.features;
		if (!features) return true;
		return features[feature] !== false;
	}
}

export { SessionStore };
export const session = new SessionStore();

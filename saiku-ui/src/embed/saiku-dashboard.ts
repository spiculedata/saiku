/*
 * Entry point for the standalone <saiku-dashboard/> bundle (issue #1103,
 * the <saiku-embed> split). Imports the Svelte 5 customElement compile
 * target — the side effect of importing the .svelte file is that Svelte
 * calls `customElements.define("saiku-dashboard", ...)`. Host pages just
 * <script src="…/saiku-dashboard.js"></script> once and the tag becomes
 * available everywhere on the page.
 *
 * The default-export `SaikuDashboard` is the constructor — exposed for
 * advanced cases (programmatic instantiation under JSDOM / SSR / a host
 * framework that wants to bypass the document parser). Not the usual
 * code path; the tag-based usage is the documented one.
 */
import SaikuDashboard from './SaikuDashboard.svelte';

export default SaikuDashboard;

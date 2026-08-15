/*
 * Entry point for the standalone <saiku-embed/> bundle. Imports the
 * Svelte 5 customElement compile target — the side effect of importing
 * the .svelte file is that Svelte calls `customElements.define("saiku-embed",
 * ...)`. Host pages just <script src="…/saiku-embed.js"></script> once
 * and the tag becomes available everywhere on the page.
 *
 * The default-export `SaikuEmbed` is the constructor — exposed for
 * advanced cases (programmatic instantiation under JSDOM / SSR / a host
 * framework that wants to bypass the document parser). Not the usual
 * code path; the tag-based usage is the documented one.
 */
import SaikuEmbed from './SaikuEmbed.svelte';

export default SaikuEmbed;

import type { StorybookConfig } from '@storybook/sveltekit';

const config: StorybookConfig = {
	// Stories live next to the components they document. Convention:
	//   src/lib/design-system/<Name>.svelte
	//   src/lib/design-system/<Name>.stories.svelte
	// New design-system components should follow this pattern so the
	// catalog stays auto-discovered.
	// Story discovery covers:
	//   - src/lib/design-system/ (canonical home for DS stories — Foundation,
	//     Primitives, Compounds all live here regardless of where the
	//     underlying component sits in the codebase)
	//   - src/lib/components/ui/ (in case anyone drops stories next to a
	//     shadcn primitive directly)
	stories: [
		'../src/lib/design-system/**/*.stories.@(svelte|ts)',
		'../src/lib/components/ui/**/*.stories.@(svelte|ts)',
		'../src/lib/**/*.mdx'
	],
	addons: [
		'@storybook/addon-svelte-csf',
		'@chromatic-com/storybook',
		'@storybook/addon-a11y',
		'@storybook/addon-docs'
	],
	framework: '@storybook/sveltekit'
};
export default config;

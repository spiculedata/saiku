import type { Preview } from '@storybook/sveltekit';
// Load saiku-ui's design tokens + Tailwind layer + global app CSS so
// components render here exactly as they do in the workbench. tokens.css
// carries every CSS variable (--fg, --bg, --accent, --success, etc.) for
// both light AND dark mode, so the theme toggle below "just works."
import '../src/lib/styles/tokens.css';
import '../src/lib/styles/tailwind.css';
import '../src/lib/styles/app.css';

const preview: Preview = {
	parameters: {
		controls: {
			matchers: {
				color: /(background|color)$/i,
				date: /Date$/i
			}
		},
		backgrounds: {
			default: 'workbench',
			values: [
				{ name: 'workbench', value: '#ffffff' },
				{ name: 'workbench dark', value: '#0b0d12' }
			]
		},
		a11y: {
			// 'todo' = show violations only in the test UI
			// 'error' = fail CI on a11y violations
			// 'off'   = skip
			test: 'todo'
		}
	},
	globalTypes: {
		theme: {
			description: 'Light / dark theme',
			defaultValue: 'light',
			toolbar: {
				title: 'Theme',
				icon: 'paintbrush',
				items: [
					{ value: 'light', title: 'Light' },
					{ value: 'dark', title: 'Dark' }
				],
				dynamicTitle: true
			}
		}
	},
	decorators: [
		(story, context) => {
			// saiku-ui's tokens.css uses `prefers-color-scheme: dark` for
			// the dark palette by default. Storybook's theme toggle uses
			// `data-theme` so we also alias it onto color-scheme via
			// the dataset → CSS variable swap. tokens.css's
			// `:root[data-theme='dark']` selector keeps the swap working.
			if (typeof document !== 'undefined') {
				const theme = (context.globals as { theme?: string }).theme ?? 'light';
				document.documentElement.dataset.theme = theme;
				document.documentElement.style.colorScheme = theme;
			}
			return story();
		}
	]
};

export default preview;

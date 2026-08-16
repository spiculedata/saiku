/**
 * Design-system barrel — re-export of the shared package.
 *
 * The components themselves live in `@concepttocloud/saiku-design-system`
 * (source at `saiku-ui/design-system/`, published from this repo under
 * Apache-2.0) so saiku-ui and saiku-cloud consume one implementation
 * instead of two copies that drift. This barrel exists so the ~70
 * in-app `$lib/design-system` imports didn't have to change.
 *
 * Import from here, not from the package directly, inside saiku-ui —
 * it keeps a single place to shim if the package ever needs adapting.
 *
 * Storybook stories for these components stay in this directory: the
 * catalogue belongs to the app, the implementation belongs to the package.
 */

export {
	PageHeader,
	FeedbackBanner,
	Badge,
	EmptyState,
	SectionCard,
	Toast,
	FormField,
	SortableColumnHeader,
	ContextMenu,
	type ContextMenuItem,
	Skeleton,
	TONES,
	TONE_CLASSES,
	SIZES,
	type Tone,
	type Size
} from '@concepttocloud/saiku-design-system';

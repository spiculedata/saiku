/**
 * UI primitives — re-export of the shared package's `/ui` entry.
 *
 * Implementations live in `@concepttocloud/saiku-design-system/ui` (source at
 * `saiku-ui/design-system/src/lib/ui/`). This barrel keeps the in-app
 * `$lib/components/ui` imports working unchanged.
 *
 * Anything that only wants button CLASSES should import
 * `@concepttocloud/saiku-design-system/ui/button-variants` directly rather than
 * going through here — this barrel pulls in every primitive, bits-ui included.
 */

export {
	Button,
	buttonVariants,
	// Layout / surface
	Card,
	CardHeader,
	CardTitle,
	CardDescription,
	CardContent,
	Divider,
	// Form controls
	Input,
	Textarea,
	Checkbox,
	Radio,
	Select,
	Switch,
	// Overlays / menus
	Tooltip,
	Popover,
	Dialog,
	DropdownMenu,
	MenuItem,
	// Misc
	Kbd,
	type ButtonVariant,
	type ButtonSize,
} from '@concepttocloud/saiku-design-system/ui';

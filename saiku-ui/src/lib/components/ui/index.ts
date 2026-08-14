/**
 * shadcn-shaped primitives — re-export of the shared package's `/ui` entry.
 *
 * Implementations live in `@concepttocloud/saiku-design-system/ui` (source at
 * `saiku-ui/design-system/src/lib/ui/`). This barrel keeps the ~68 in-app
 * `$lib/components/ui` imports working unchanged.
 */

export {
	Button,
	buttonVariants,
	Card,
	CardHeader,
	CardTitle,
	CardDescription,
	CardContent,
	Input,
	Tooltip,
	type ButtonVariant,
	type ButtonSize,
} from '@concepttocloud/saiku-design-system/ui';

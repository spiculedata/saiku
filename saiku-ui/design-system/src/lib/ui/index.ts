/**
 * UI primitives — the ground floor of the design system.
 *
 * Every primitive binds only to design-system tokens (colours, spacing, radii,
 * typography) and standardises one API for a common UI atom. Import from this
 * barrel rather than reaching into the files.
 *
 * Anything that only wants button CLASSES (not the component) should import
 * '@concepttocloud/saiku-design-system/ui/button-variants' directly — this
 * barrel pulls in every primitive, including bits-ui via Dialog/Popover.
 */

export { default as Button } from './button.svelte';
// Variants come straight from the .ts module, not through button.svelte —
// routing the class contract through a component would make
// `import { buttonVariants }` drag the component graph in.
export { buttonVariants } from './button-variants';
export type { ButtonVariant, ButtonSize } from './button-variants';

// Layout / surface
export { default as Card } from './card.svelte';
export { default as CardHeader } from './card-header.svelte';
export { default as CardTitle } from './card-title.svelte';
export { default as CardDescription } from './card-description.svelte';
export { default as CardContent } from './card-content.svelte';
export { default as Divider } from './Divider.svelte';

// Form controls
export { default as Input } from './input.svelte';
export { default as Textarea } from './Textarea.svelte';
export { default as Checkbox } from './Checkbox.svelte';
export { default as Radio } from './Radio.svelte';
export { default as Select } from './Select.svelte';
export { default as Switch } from './Switch.svelte';

// Overlays / menus
export { default as Tooltip } from './tooltip.svelte';
export { default as Popover } from './Popover.svelte';
export { default as Dialog } from './Dialog.svelte';
export { default as DropdownMenu } from './DropdownMenu.svelte';
export { default as MenuItem } from './MenuItem.svelte';

// Misc
export { default as Kbd } from './Kbd.svelte';

import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Merge conditional class values, letting later Tailwind utilities win over
 * earlier ones in the same group (`px-2` + `px-4` → `px-4`, not both).
 *
 * This is the same `cn` both consuming apps already ship at `$lib/utils`; it
 * lives here so the package has no dependency on either app's lib layout.
 * The apps keep their own `$lib/utils` for app-specific helpers.
 */
export function cn(...inputs: ClassValue[]): string {
	return twMerge(clsx(inputs));
}

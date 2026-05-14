export type ToastVariant = "info" | "success" | "warning" | "danger";

export interface Toast {
  id: number;
  variant: ToastVariant;
  title: string;
  body?: string;
  createdAt: number;
}

/** Collapse bursts of near-identical toasts. If the same (variant, title) was
 *  pushed within this window, we skip the second one — avoids stacks of
 *  duplicates when an effect fires repeatedly (e.g. axis changes while the
 *  user drags). */
const DEDUPE_MS = 2000;

class ToastStore {
  toasts = $state<Toast[]>([]);
  private nextId = 1;
  private lastPush = new Map<string, number>();

  push(variant: ToastVariant, title: string, body?: string, ttl?: number): number {
    const now = Date.now();
    const key = `${variant}::${title}`;
    const prev = this.lastPush.get(key) ?? 0;
    if (now - prev < DEDUPE_MS) return -1;
    this.lastPush.set(key, now);

    // Default TTLs: success (done-ish) is shortest, info is medium,
    // warning is a bit longer because the user needs to read it, and
    // danger sticks until dismissed so a failure never flashes past.
    const effectiveTtl = ttl ?? (
      variant === "success" ? 3000
      : variant === "info" ? 5000
      : variant === "warning" ? 6000
      : 0 /* danger: sticky */
    );

    const id = this.nextId++;
    this.toasts.push({ id, variant, title, body, createdAt: now });
    if (effectiveTtl > 0) {
      setTimeout(() => this.dismiss(id), effectiveTtl);
    }
    return id;
  }

  dismiss(id: number): void {
    this.toasts = this.toasts.filter((t) => t.id !== id);
  }

  info(title: string, body?: string) { return this.push("info", title, body); }
  success(title: string, body?: string) { return this.push("success", title, body); }
  warning(title: string, body?: string) { return this.push("warning", title, body); }
  danger(title: string, body?: string) { return this.push("danger", title, body); }
}

export const toasts = new ToastStore();

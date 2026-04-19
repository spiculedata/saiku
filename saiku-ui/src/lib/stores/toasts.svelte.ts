export type ToastVariant = "info" | "success" | "warning" | "danger";

export interface Toast {
  id: number;
  variant: ToastVariant;
  title: string;
  body?: string;
  createdAt: number;
}

class ToastStore {
  toasts = $state<Toast[]>([]);
  private nextId = 1;

  push(variant: ToastVariant, title: string, body?: string, ttl = 4500): number {
    const id = this.nextId++;
    this.toasts.push({ id, variant, title, body, createdAt: Date.now() });
    if (ttl > 0) {
      setTimeout(() => this.dismiss(id), ttl);
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

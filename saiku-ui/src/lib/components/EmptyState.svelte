<script lang="ts">
  /*
   * Empty-state component for list views, modals, admin tables.
   *
   * Communicates "nothing is here yet" with shape (icon) + narrative
   * (title + description) + optional next action. Far easier to act on
   * than a one-line "No saved queries." grey paragraph.
   *
   * Pass a lucide-svelte icon component as `icon`; the wrapper sizes
   * it consistently.
   */

  interface ActionProp {
    label: string;
    onClick: () => void;
  }

  interface Props {
    /** A lucide-svelte (or compatible) icon component class. Typed
     *  permissively because lucide's own component type carries extra
     *  generic params that a tighter signature won't accept. */
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    icon?: any;
    title: string;
    description?: string;
    action?: ActionProp;
    /** When true, vertical padding collapses for use inside small
     *  modal bodies where breathing room isn't appropriate. */
    compact?: boolean;
  }

  let { icon: Icon, title, description, action, compact = false }: Props = $props();
</script>

<div class="empty-state" class:empty-state--compact={compact}>
  {#if Icon}
    <span class="empty-state__icon" aria-hidden="true">
      <Icon size={48} />
    </span>
  {/if}
  <h3 class="empty-state__title">{title}</h3>
  {#if description}
    <p class="empty-state__description">{description}</p>
  {/if}
  {#if action}
    <button type="button" class="btn btn--primary" onclick={action.onClick}>
      {action.label}
    </button>
  {/if}
</div>

<style>
  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    gap: var(--space-3);
    padding: var(--space-8) var(--space-5);
    color: var(--fg);
  }

  .empty-state--compact {
    padding: var(--space-5) var(--space-3);
    gap: var(--space-2);
  }

  .empty-state__icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 64px;
    height: 64px;
    border-radius: 50%;
    background: var(--bg-muted);
    color: var(--fg-subtle);
  }

  .empty-state--compact .empty-state__icon {
    width: 48px;
    height: 48px;
  }

  .empty-state__title {
    margin: 0;
    font-size: var(--fs-lg);
    font-weight: var(--weight-semibold);
    color: var(--fg);
  }

  .empty-state__description {
    margin: 0;
    max-width: 32ch;
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    line-height: var(--lh-normal);
  }
</style>

<script lang="ts">
  import { Button } from "$lib/components/ui";
  /*
   * Empty-state component for list views, modals, admin tables.
   *
   * Communicates "nothing is here yet" with shape (icon) + narrative
   * (title + description) + optional next action. Far easier to act on
   * than a one-line "No saved queries." grey paragraph.
   *
   * Pass a lucide icon component as `icon`; the wrapper sizes
   * it consistently.
   */

  interface ActionProp {
    label: string;
    onClick: () => void;
  }

  interface Props {
    /** A lucide (or compatible) icon component class. Typed
     *  permissively because lucide's own component type carries extra
     *  generic params that a tighter signature won't accept. */
     
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
  <h3 class="m-0 text-lg font-semibold text-fg">{title}</h3>
  {#if description}
    <p class="empty-state__description">{description}</p>
  {/if}
  {#if action}
    <Button onclick={action.onClick}>
      {action.label}
    </Button>
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
    color: hsl(var(--fg));
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
    background: hsl(var(--bg-muted));
    color: hsl(var(--fg-subtle));
  }
  .empty-state--compact .empty-state__icon {
    width: 48px;
    height: 48px;
  }
  .empty-state__description {
    margin: 0;
    max-width: 32ch;
    color: hsl(var(--fg-muted));
    font-size: var(--fs-sm);
    line-height: var(--lh-normal);
  }
</style>

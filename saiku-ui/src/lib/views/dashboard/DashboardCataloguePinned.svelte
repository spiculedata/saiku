<script lang="ts">
  /*
   * Pinned sections — Favourites + "Recently viewed" — extracted from
   * DashboardIndex (saiku#1234 Phase 2). Both render a small list of
   * dashboards above the main catalogue when in list view. The two
   * snippets share the same `.row` / `.link` styling but differ in
   * the action button set:
   *
   *   - favourites: a single "remove" star (always star--on)
   *   - recents:    no action buttons; clicking the link is the
   *                 whole interaction.
   *
   * Parent provides the resolved entry arrays (already filtered against
   * the live listing for ACL / deleted paths). `toRepoRelative` is
   * imported here to avoid threading the helper through a prop.
   */
  import { base } from "$app/paths";
  import { Button } from "$lib/components/ui";
  import { Star } from "lucide-svelte";
  import { toRepoRelative, displayPath } from "$lib/api/dashboards";
  import type { RepositoryNode } from "$lib/api/repository";

  interface Props {
    favourites: RepositoryNode[];
    recents: RepositoryNode[];
    onToggleFavourite: (relPath: string) => void;
    /** Resolves a node to its human label — the dashboard's own title when
     *  known, else its filename slug. Lets the pinned rows read the real
     *  title rather than a raw `.saikudash` filename. (#1605) */
    titleFor: (node: RepositoryNode) => string;
  }

  let { favourites, recents, onToggleFavourite, titleFor }: Props = $props();
</script>

{#if favourites.length > 0}
  <section class="pinned" aria-labelledby="favourites-heading">
    <h2 id="favourites-heading" class="pinned-heading">
      <Star size={14} aria-hidden="true" /> Favourites
    </h2>
    <ul class="list-none m-0 p-0 flex flex-col gap-1">
      {#each favourites as e (e.path)}
        {@const relPath = toRepoRelative(e.path)}
        <li class="row">
          <a class="link" href="{base}/dashboards/{relPath}" aria-label={titleFor(e)} title={displayPath(relPath)}>
            <span class="name">{titleFor(e)}</span>
            <span class="text-fg-muted text-xs overflow-hidden text-ellipsis whitespace-nowrap">{displayPath(relPath)}</span>
          </a>
          <Button variant="outline" class="icon-only star star--on" onclick={() => onToggleFavourite(relPath)} title="Remove from favourites" aria-label="Remove from favourites" aria-pressed="true">
            <Star size={14} fill="currentColor" />
          </Button>
        </li>
      {/each}
    </ul>
  </section>
{/if}

{#if recents.length > 0}
  <section class="pinned" aria-labelledby="recents-heading">
    <h2 id="recents-heading" class="pinned-heading">🕒 Recently viewed</h2>
    <ul class="list-none m-0 p-0 flex flex-col gap-1">
      {#each recents as e (e.path)}
        {@const relPath = toRepoRelative(e.path)}
        <li class="row">
          <a class="link" href="{base}/dashboards/{relPath}" aria-label={titleFor(e)} title={displayPath(relPath)}>
            <span class="name">{titleFor(e)}</span>
            <span class="text-fg-muted text-xs overflow-hidden text-ellipsis whitespace-nowrap">{displayPath(relPath)}</span>
          </a>
        </li>
      {/each}
    </ul>
  </section>
{/if}

<style>
/* Mirrors DashboardIndex's pinned + row styles. Duplicated because
     Svelte scoped CSS does not cross component boundaries. */
  .pinned {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    padding: 0.5rem 0;
  }
  .pinned-heading {
    font-size: 0.6875rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
    font-weight: var(--weight-semibold);
    margin: 0 0 0.25rem;
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.5rem 0.625rem;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
  }
  .link {
    flex: 1;
    display: flex;
    flex-direction: column;
    text-decoration: none;
    color: var(--fg);
    min-width: 0;
  }
  .link:hover .name {
    color: var(--accent);
  }
  .name {
    font-weight: var(--weight-semibold);
    font-size: 0.9375rem;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>

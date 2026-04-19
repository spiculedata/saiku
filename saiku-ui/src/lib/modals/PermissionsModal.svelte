<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/PermissionsModal.js. */
  export type AclType = "PRIVATE" | "SECURED" | "PUBLIC";
  export type AclMethod = "READ" | "WRITE" | "GRANT";

  export interface Acl {
    type: AclType;
    owner: string;
    roles: Record<string, AclMethod[]>;
    users: Record<string, AclMethod[]>;
  }

  interface Props {
    path: string;
    allRoles: string[];
    initial: Acl;
    open: boolean;
    onSave: (acl: Acl) => void;
    onCancel: () => void;
  }

  let { path, allRoles, initial, open, onSave, onCancel }: Props = $props();
  let acl = $state<Acl>(structuredClone(initial));

  $effect(() => {
    if (open) acl = structuredClone(initial);
  });

  function toggleRoleMethod(role: string, method: AclMethod) {
    const list = acl.roles[role] ?? [];
    const has = list.includes(method);
    acl.roles[role] = has ? list.filter((m) => m !== method) : [...list, method];
  }
</script>

<Modal title={`Permissions — ${path}`} {open} size="lg" onClose={onCancel}>
  <label class="field">
    <span class="field__label">Visibility</span>
    <select class="field__input" bind:value={acl.type}>
      <option value="PRIVATE">Private (owner only)</option>
      <option value="SECURED">Secured (per role/user)</option>
      <option value="PUBLIC">Public</option>
    </select>
  </label>
  {#if acl.type === "SECURED"}
    <table class="grid">
      <thead>
        <tr><th>Role</th><th>Read</th><th>Write</th><th>Grant</th></tr>
      </thead>
      <tbody>
        {#each allRoles as r}
          <tr>
            <td>{r}</td>
            {#each ["READ", "WRITE", "GRANT"] as const as m}
              <td class="cell">
                <input
                  type="checkbox"
                  checked={(acl.roles[r] ?? []).includes(m)}
                  onchange={() => toggleRoleMethod(r, m)}
                />
              </td>
            {/each}
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button type="button" class="btn btn--primary" onclick={() => onSave(acl)}>Save</button>
  {/snippet}
</Modal>

<style>
  .grid { width: 100%; border-collapse: collapse; margin-top: var(--space-2); font-size: var(--fs-sm); }
  .grid th, .grid td { border: 1px solid var(--border); padding: var(--space-1) var(--space-2); text-align: left; }
  .grid th { background: var(--bg-muted); }
  .cell { text-align: center; }
</style>

<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type { AclEntry, AclMethod } from "$lib/api/repository";

  interface Props {
    path: string;
    allRoles: string[];
    initial: AclEntry;
    open: boolean;
    onSave: (acl: AclEntry) => void;
    onCancel: () => void;
  }

  let { path, allRoles, initial, open, onSave, onCancel }: Props = $props();
  let acl = $state<AclEntry>(untrack(() => structuredClone(initial)));

  $effect(() => {
    if (open) acl = structuredClone(initial);
  });

  function toggleRoleMethod(role: string, method: AclMethod) {
    const list = acl.roles[role] ?? [];
    const has = list.includes(method);
    acl.roles[role] = has ? list.filter((m) => m !== method) : [...list, method];
  }
</script>

<Modal title={`${i18n.t("modal.permissions.title")} — ${path}`} {open} size="lg" onClose={onCancel}>
  <label class="field">
    <span class="field__label">{i18n.t("modal.permissions.visibility")}</span>
    <select class="field__input" bind:value={acl.type}>
      <option value="PRIVATE">{i18n.t("modal.permissions.private")}</option>
      <option value="SECURED">{i18n.t("modal.permissions.secured")}</option>
      <option value="PUBLIC">{i18n.t("modal.permissions.public")}</option>
    </select>
  </label>
  {#if acl.type === "SECURED"}
    <table class="grid">
      <thead>
        <tr><th>{i18n.t("modal.permissions.role")}</th><th>{i18n.t("modal.permissions.read")}</th><th>{i18n.t("modal.permissions.write")}</th><th>{i18n.t("modal.permissions.grant")}</th></tr>
      </thead>
      <tbody>
        {#each allRoles as r}
          <tr>
            <td>{r}</td>
            {#each ["READ", "WRITE", "GRANT"] as const as m}
              <td class="text-center">
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
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button onclick={() => onSave(acl)}>{i18n.t("modal.save")}</Button>
  {/snippet}
</Modal>

<style>
.grid { width: 100%; border-collapse: collapse; margin-top: var(--space-2); font-size: var(--fs-sm); }
  .grid th, .grid td { border: 1px solid var(--border); padding: var(--space-1) var(--space-2); text-align: left; }
  .grid th { background: var(--bg-muted); }
</style>

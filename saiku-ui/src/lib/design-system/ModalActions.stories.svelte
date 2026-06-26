<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import ModalActions from "$lib/modals/parts/ModalActions.svelte";

  const { Story } = defineMeta({
    title: "Compounds/ModalActions",
    component: ModalActions,
    tags: ["autodocs"],
    parameters: {
      docs: {
        description: {
          component:
            "Shared Cancel + primary footer for modals — drop into a Modal's `{#snippet footer()}` slot to avoid hand-rolling the same pair of Button primitives in every modal. Used by 7+ modal surfaces.",
        },
      },
    },
  });
</script>

<Story name="OK">
  <div class="rounded-md border border-border bg-bg-muted p-4">
    <div class="flex justify-end gap-2">
      <ModalActions onCancel={() => {}} onApply={() => {}} />
    </div>
  </div>
</Story>

<Story name="Save">
  <div class="rounded-md border border-border bg-bg-muted p-4">
    <div class="flex justify-end gap-2">
      <ModalActions
        onCancel={() => {}}
        onApply={() => {}}
        primaryKey="modal.save"
      />
    </div>
  </div>
</Story>

<Story name="Disabled">
  <div class="rounded-md border border-border bg-bg-muted p-4">
    <div class="flex justify-end gap-2">
      <ModalActions
        onCancel={() => {}}
        onApply={() => {}}
        primaryKey="modal.apply"
        enabled={false}
      />
    </div>
  </div>
</Story>

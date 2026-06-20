<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import Modal from "$lib/components/Modal.svelte";

  const { Story } = defineMeta({
    title: "Compounds/Modal",
    component: Modal,
    tags: ["autodocs"],
    argTypes: {
      size: { control: "radio", options: ["sm", "md", "lg", "xl"] },
    },
    parameters: {
      docs: {
        description: {
          component:
            "Focus-trapped dialog with backdrop + header + body + footer. Reused 25+ times across the workbench. Behaviour is load-bearing: focus traps inside the panel, Escape closes, focus restores to the previously-focused element on close.",
        },
      },
    },
  });
</script>

<script lang="ts">
  import { Button } from "$lib/components/ui";
</script>

<Story name="Small">
  <Modal title="Confirm delete" open={true} size="sm" onClose={() => {}}>
    <p>Are you sure you want to delete this saved query?</p>
    {#snippet footer()}
      <Button variant="outline">Cancel</Button>
      <Button variant="destructive">Delete</Button>
    {/snippet}
  </Modal>
</Story>

<Story name="Medium">
  <Modal title="Save query" open={true} size="md" onClose={() => {}}>
    <div class="flex flex-col gap-3">
      <label class="flex flex-col gap-1 text-sm">
        <span class="font-medium">Name</span>
        <input
          class="rounded border border-input bg-bg px-3 py-2 text-sm"
          placeholder="Untitled.saiku"
        />
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="font-medium">Folder</span>
        <select class="rounded border border-input bg-bg px-3 py-2 text-sm">
          <option>/homes/admin</option>
        </select>
      </label>
    </div>
    {#snippet footer()}
      <Button variant="outline">Cancel</Button>
      <Button>Save</Button>
    {/snippet}
  </Modal>
</Story>

<Story name="Large">
  <Modal title="Drillthrough results" open={true} size="lg" onClose={() => {}}>
    <p>
      Large modal — 760px wide. For tables, grids, or any content that
      benefits from horizontal breathing room.
    </p>
    {#snippet footer()}
      <Button variant="outline">Close</Button>
    {/snippet}
  </Modal>
</Story>

<Story name="ExtraLarge">
  <Modal title="Chart editor" open={true} size="xl" onClose={() => {}}>
    <p>
      Extra-large modal — 960px wide. For the chart editor and other
      densely-tabbed configuration surfaces.
    </p>
    {#snippet footer()}
      <Button variant="outline">Cancel</Button>
      <Button>Apply</Button>
    {/snippet}
  </Modal>
</Story>

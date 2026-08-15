<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import { ContextMenu } from "$lib/design-system";

  const { Story } = defineMeta({
    title: "Compounds/ContextMenu",
    component: ContextMenu,
    tags: ["autodocs"],
    parameters: {
      docs: {
        description: {
          component:
            "Positioned right-click menu — used wherever the user can long-press / right-click a row, tile, or member to act on it. Caller owns position (x/y) + open state; clicking outside or picking an item fires onClose.",
        },
      },
    },
  });
</script>

<script lang="ts">
  let openA = $state(true);
  let openB = $state(true);
  let openC = $state(true);

  const queryItems = [
    { id: "open", label: "Open" },
    { id: "rename", label: "Rename" },
    { id: "duplicate", label: "Duplicate" },
    { id: "sep1", label: "", sep: true },
    { id: "delete", label: "Delete", danger: true },
  ];

  const memberItems = [
    { id: "filter", label: "Include only this member" },
    { id: "exclude", label: "Exclude this member" },
    { id: "drillthrough", label: "Drill through…" },
    { id: "sep1", label: "", sep: true },
    { id: "copy", label: "Copy unique name" },
  ];

  const disabledItems = [
    { id: "edit", label: "Edit", disabled: true },
    { id: "share", label: "Share" },
    { id: "sep1", label: "", sep: true },
    { id: "delete", label: "Delete", disabled: true, danger: true },
  ];
</script>

<Story name="SavedQueryActions">
  <div class="relative h-64">
    <ContextMenu
      open={openA}
      x={20}
      y={20}
      items={queryItems}
      onPick={() => {}}
      onClose={() => (openA = false)}
    />
  </div>
</Story>

<Story name="MemberActions">
  <div class="relative h-64">
    <ContextMenu
      open={openB}
      x={20}
      y={20}
      items={memberItems}
      onPick={() => {}}
      onClose={() => (openB = false)}
    />
  </div>
</Story>

<Story name="WithDisabledItems">
  <div class="relative h-64">
    <ContextMenu
      open={openC}
      x={20}
      y={20}
      items={disabledItems}
      onPick={() => {}}
      onClose={() => (openC = false)}
    />
  </div>
</Story>

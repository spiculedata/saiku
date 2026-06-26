<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import ModalModeSwitch from "$lib/modals/parts/ModalModeSwitch.svelte";

  const { Story } = defineMeta({
    title: "Compounds/ModalModeSwitch",
    component: ModalModeSwitch,
    tags: ["autodocs"],
    parameters: {
      docs: {
        description: {
          component:
            "Two-tab strip — used in the axis-filter modal family (Order / TopBottomCount / Limit) to switch between the simple picker and the MDX-escape-hatch view. Controlled by the parent.",
        },
      },
    },
  });
</script>

<script lang="ts">
  let modeA = $state<"simple" | "mdx">("simple");
  let modeB = $state<"simple" | "mdx">("mdx");
</script>

<Story name="SimpleSelected">
  <div class="p-4">
    <ModalModeSwitch mode={modeA} onChange={(m) => (modeA = m)} />
    <p class="mt-3 text-sm text-fg-muted">Current mode: <code>{modeA}</code></p>
  </div>
</Story>

<Story name="MdxSelected">
  <div class="p-4">
    <ModalModeSwitch mode={modeB} onChange={(m) => (modeB = m)} />
    <p class="mt-3 text-sm text-fg-muted">Current mode: <code>{modeB}</code></p>
  </div>
</Story>

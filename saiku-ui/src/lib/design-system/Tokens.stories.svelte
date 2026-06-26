<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  const { Story } = defineMeta({
    title: "Foundation/Tokens",
    tags: ["autodocs"],
    parameters: {
      docs: {
        description: {
          component:
            "saiku-ui's design tokens — the 133 CSS variables in `tokens.css` that everything else resolves through. Surfaces (background/foreground), borders, brand colours, status tones, spacing scale, radii, and type scale. Light + dark modes use distinct values; toggle the theme in the Storybook toolbar to flip every swatch.",
        },
      },
    },
  });

  const colorSurfaces = [
    { name: "--bg",         class: "bg-bg" },
    { name: "--bg-muted",   class: "bg-bg-muted" },
    { name: "--bg-subtle",  class: "bg-bg-subtle" },
    { name: "--bg-hover",   class: "bg-bg-hover" },
  ];
  const colorForegrounds = [
    { name: "--fg",         class: "text-fg" },
    { name: "--fg-muted",   class: "text-fg-muted" },
    { name: "--fg-subtle",  class: "text-fg-subtle" },
  ];
  const colorBrand = [
    { name: "--accent",         class: "bg-accent" },
    { name: "--accent-hover",   class: "bg-accent-hover" },
    { name: "--accent-soft",    class: "bg-accent-soft" },
    { name: "--accent-strong",  class: "bg-accent-strong" },
  ];
  const colorTones = [
    { name: "--success",         class: "bg-success" },
    { name: "--success-soft",    class: "bg-success-soft" },
    { name: "--success-strong",  class: "bg-success-strong" },
    { name: "--warning",         class: "bg-warning" },
    { name: "--warning-soft",    class: "bg-warning-soft" },
    { name: "--warning-strong",  class: "bg-warning-strong" },
    { name: "--danger",          class: "bg-danger" },
    { name: "--danger-soft",     class: "bg-danger-soft" },
    { name: "--danger-strong",   class: "bg-danger-strong" },
  ];

  const spacing = [
    { name: "--space-1 (4px)",  class: "h-1  w-4"  },
    { name: "--space-2 (8px)",  class: "h-2  w-8"  },
    { name: "--space-3 (12px)", class: "h-3  w-12" },
    { name: "--space-4 (16px)", class: "h-4  w-16" },
    { name: "--space-5 (24px)", class: "h-5  w-24" },
    { name: "--space-6 (32px)", class: "h-6  w-32" },
    { name: "--space-8 (48px)", class: "h-8  w-48" },
  ];

  const radii = [
    { name: "--radius-sm",  class: "rounded-sm"  },
    { name: "--radius-md",  class: "rounded-md"  },
    { name: "--radius-lg",  class: "rounded-lg"  },
  ];

  const typeScale = [
    { name: "--fs-xs (12px)",  class: "text-xs"   },
    { name: "--fs-sm (13px)",  class: "text-sm"   },
    { name: "--fs-md (14px)",  class: "text-base" },
    { name: "--fs-lg (16px)",  class: "text-lg"   },
    { name: "--fs-xl (20px)",  class: "text-xl"   },
    { name: "--fs-2xl (28px)", class: "text-2xl"  },
  ];
</script>

<Story name="ColourSurfaces">
  <div class="space-y-6 p-6">
    <section>
      <h3 class="mb-3 text-lg font-semibold">Surfaces</h3>
      <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
        {#each colorSurfaces as t}
          <div class="rounded-md border border-border overflow-hidden">
            <div class="h-16 {t.class}"></div>
            <div class="border-t border-border bg-bg px-3 py-2 text-xs">
              <code class="text-fg">{t.name}</code>
            </div>
          </div>
        {/each}
      </div>
    </section>
    <section>
      <h3 class="mb-3 text-lg font-semibold">Foreground text</h3>
      <div class="flex flex-col gap-2">
        {#each colorForegrounds as t}
          <div class="rounded-md border border-border bg-bg px-3 py-2 text-sm">
            <span class={t.class}>Aa — the quick brown fox jumps over the lazy dog</span>
            <code class="ml-3 text-xs text-fg-subtle">{t.name}</code>
          </div>
        {/each}
      </div>
    </section>
  </div>
</Story>

<Story name="Brand">
  <div class="p-6">
    <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
      {#each colorBrand as t}
        <div class="rounded-md border border-border overflow-hidden">
          <div class="h-16 {t.class}"></div>
          <div class="border-t border-border bg-bg px-3 py-2 text-xs">
            <code class="text-fg">{t.name}</code>
          </div>
        </div>
      {/each}
    </div>
  </div>
</Story>

<Story name="Tones">
  <div class="p-6">
    <div class="grid grid-cols-3 gap-3">
      {#each colorTones as t}
        <div class="rounded-md border border-border overflow-hidden">
          <div class="h-12 {t.class}"></div>
          <div class="border-t border-border bg-bg px-3 py-2 text-xs">
            <code class="text-fg">{t.name}</code>
          </div>
        </div>
      {/each}
    </div>
  </div>
</Story>

<Story name="Spacing">
  <div class="p-6">
    <div class="flex flex-col gap-3">
      {#each spacing as t}
        <div class="flex items-center gap-4">
          <div class="bg-accent {t.class}"></div>
          <code class="text-sm text-fg-muted">{t.name}</code>
        </div>
      {/each}
    </div>
  </div>
</Story>

<Story name="Radii">
  <div class="p-6">
    <div class="flex flex-wrap gap-4">
      {#each radii as t}
        <div class="flex flex-col items-center gap-2">
          <div class="h-16 w-16 border-2 border-accent bg-accent-soft {t.class}"></div>
          <code class="text-xs text-fg-muted">{t.name}</code>
        </div>
      {/each}
    </div>
  </div>
</Story>

<Story name="Typography">
  <div class="p-6">
    <div class="flex flex-col gap-3">
      {#each typeScale as t}
        <div>
          <span class={t.class}>The five boxing wizards jump quickly</span>
          <code class="ml-3 text-xs text-fg-subtle">{t.name}</code>
        </div>
      {/each}
    </div>
  </div>
</Story>

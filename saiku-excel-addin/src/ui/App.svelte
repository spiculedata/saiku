<script lang="ts">
  import { onMount } from "svelte";
  import { session } from "../lib/auth";
  import type { SavedQuery } from "../lib/queryStore";
  import Login from "./Login.svelte";
  import Home from "./Home.svelte";
  import Wizard from "./Wizard.svelte";

  let loggedIn = $state(false);
  let view = $state<"home" | "wizard">("home");
  let editing = $state<SavedQuery | null>(null);
  let loading = $state(true);

  onMount(async () => {
    try {
      await session();
      loggedIn = true;
    } catch {
      loggedIn = false;
    } finally {
      loading = false;
    }
  });
</script>

<main style="font-family: Segoe UI, system-ui, sans-serif; padding: 0; width: 320px; box-sizing: border-box;">
  {#if loading}
    <div style="padding: 24px; text-align: center; color: #666;">Loading…</div>
  {:else if !loggedIn}
    <Login onLoggedIn={() => { loggedIn = true; view = "home"; }} />
  {:else if view === "home"}
    <Home
      onNew={() => { editing = null; view = "wizard"; }}
      onEdit={(q) => { editing = q; view = "wizard"; }}
    />
  {:else}
    <Wizard
      initial={editing}
      onDone={() => { editing = null; view = "home"; }}
      onCancel={() => { editing = null; view = "home"; }}
    />
  {/if}
</main>

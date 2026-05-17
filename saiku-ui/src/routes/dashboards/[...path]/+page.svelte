<script lang="ts">
  /*
   * Dashboards route.
   *
   * Opens DashboardEditor at the JCR path supplied via the rest segment.
   * The editor handles loading, error display, save, and the inner
   * toolbar/grid/filter-bar composition. This route is the thin shell.
   *
   * Anonymous / unauthenticated users will hit the same 401 path as the
   * rest of the app — covered by the session listener wired in the root
   * layout.
   */

  import DashboardEditor from "$lib/views/dashboard/DashboardEditor.svelte";

  let { data } = $props();
</script>

<DashboardEditor dashboardPath={data.dashboardPath} />

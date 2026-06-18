<script lang="ts">
  const logo = `${import.meta.env.BASE_URL}assets/logo.svg`;
</script>
<header class="hd">
  <img class="logo" src={logo} alt="Saiku" />
  <span class="sub">for Excel</span>
</header>
<style>
  .hd { display: flex; align-items: center; gap: var(--space-2);
        padding: var(--space-3); background: var(--bg); border-bottom: 1px solid var(--border); }
  .logo { height: 20px; width: auto; }
  .sub { color: var(--fg-muted); font-size: var(--fs-xs); }
</style>

<script lang="ts">
  import { login } from "../lib/auth";

  const { onLoggedIn }: { onLoggedIn: () => void } = $props();

  let username = $state("");
  let password = $state("");
  let error = $state<string | null>(null);
  let busy = $state(false);

  async function handleSubmit(e: SubmitEvent) {
    e.preventDefault();
    error = null;
    busy = true;
    try {
      await login(username, password);
      onLoggedIn();
    } catch {
      error = "Login failed. Check your username and password.";
    } finally {
      busy = false;
    }
  }
</script>

<div class="login-container">
  <div class="logo">
    <svg width="32" height="32" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
      <rect width="32" height="32" rx="6" fill="#0078d4"/>
      <text x="6" y="23" font-family="Segoe UI" font-size="18" font-weight="bold" fill="white">S</text>
    </svg>
    <span class="app-name">Saiku</span>
  </div>

  <h2>Sign in</h2>

  <form onsubmit={handleSubmit}>
    <div class="field">
      <label for="username">Username</label>
      <input
        id="username"
        type="text"
        autocomplete="username"
        bind:value={username}
        disabled={busy}
        required
      />
    </div>

    <div class="field">
      <label for="password">Password</label>
      <input
        id="password"
        type="password"
        autocomplete="current-password"
        bind:value={password}
        disabled={busy}
        required
      />
    </div>

    {#if error}
      <div class="error" role="alert">{error}</div>
    {/if}

    <button type="submit" class="btn-primary" disabled={busy}>
      {#if busy}Signing in…{:else}Sign in{/if}
    </button>
  </form>
</div>

<style>
  .login-container {
    padding: 24px 20px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .logo {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
  }

  .app-name {
    font-size: 18px;
    font-weight: 600;
    color: #0078d4;
  }

  h2 {
    font-size: 16px;
    font-weight: 600;
    margin: 0 0 12px;
    color: #323130;
  }

  form {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .field {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  label {
    font-size: 13px;
    font-weight: 600;
    color: #323130;
  }

  input {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 14px;
    padding: 6px 8px;
    border: 1px solid #8a8886;
    border-radius: 2px;
    outline: none;
    width: 100%;
    box-sizing: border-box;
  }

  input:focus {
    border-color: #0078d4;
    box-shadow: 0 0 0 1px #0078d4;
  }

  input:disabled {
    background: #f3f2f1;
    color: #a19f9d;
  }

  .error {
    font-size: 13px;
    color: #a4262c;
    padding: 6px 8px;
    background: #fde7e9;
    border-radius: 2px;
  }

  .btn-primary {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 14px;
    font-weight: 600;
    color: #fff;
    background: #0078d4;
    border: none;
    border-radius: 2px;
    padding: 8px 16px;
    cursor: pointer;
    margin-top: 4px;
    width: 100%;
  }

  .btn-primary:hover:not(:disabled) {
    background: #106ebe;
  }

  .btn-primary:disabled {
    background: #c8c6c4;
    cursor: default;
  }
</style>

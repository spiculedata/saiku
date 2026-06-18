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
    padding: var(--space-5) var(--space-4);
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  h2 {
    font-size: var(--fs-lg);
    font-weight: var(--fw-semibold);
    margin: 0 0 var(--space-3);
    color: var(--fg);
  }

  form {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .field {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  label {
    font-size: var(--fs-sm);
    font-weight: var(--fw-semibold);
    color: var(--fg);
  }

  input {
    font-family: var(--font);
    font-size: var(--fs-md);
    padding: 6px 8px;
    border: 1px solid var(--fg-subtle);
    border-radius: var(--radius-sm);
    outline: none;
    width: 100%;
    box-sizing: border-box;
    color: var(--fg);
    background: var(--bg);
  }

  input:focus {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-soft);
  }

  input:disabled {
    background: var(--bg-muted);
    color: var(--fg-subtle);
  }

  .error {
    font-size: var(--fs-sm);
    color: var(--danger);
    padding: 6px 8px;
    background: var(--danger-soft);
    border-radius: var(--radius-sm);
  }

  .btn-primary {
    font-family: var(--font);
    font-size: var(--fs-md);
    font-weight: var(--fw-semibold);
    color: var(--on-accent);
    background: var(--accent);
    border: none;
    border-radius: var(--radius-sm);
    padding: 8px var(--space-4);
    cursor: pointer;
    margin-top: var(--space-1);
    width: 100%;
    box-shadow: var(--shadow-sm);
  }

  .btn-primary:hover:not(:disabled) {
    background: var(--accent-hover);
  }

  .btn-primary:disabled {
    background: var(--border-strong);
    cursor: default;
  }
</style>

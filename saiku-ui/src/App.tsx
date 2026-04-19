import { useEffect, useState } from "react";
import { getCurrentSession, logout, type SaikuSession } from "./api/session";
import { Login } from "./components/Login";
import { Workspace } from "./components/Workspace";
import { useTheme } from "./theme";

type State =
  | { kind: "loading" }
  | { kind: "anonymous" }
  | { kind: "signed-in"; session: SaikuSession };

export function App() {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [theme, setTheme] = useTheme();

  useEffect(() => {
    (async () => {
      const session = await getCurrentSession();
      setState(session ? { kind: "signed-in", session } : { kind: "anonymous" });
    })();
  }, []);

  async function onLogout() {
    await logout();
    setState({ kind: "anonymous" });
  }

  function toggleTheme() {
    setTheme(theme === "dark" ? "light" : "dark");
  }

  return (
    <div className="app">
      <header className="topbar">
        <span className="topbar__brand">saiku</span>
        <div className="topbar__user">
          <button
            type="button"
            className="theme-toggle"
            onClick={toggleTheme}
            aria-label="Toggle theme"
          >
            {theme === "dark" ? "☾" : "☀"} {theme}
          </button>
          {state.kind === "signed-in" && (
            <>
              <span>{state.session.username}</span>
              <button type="button" className="theme-toggle" onClick={onLogout}>
                Sign out
              </button>
            </>
          )}
        </div>
      </header>
      {state.kind === "loading" && (
        <div style={{ margin: "auto", color: "var(--fg-muted)" }}>Loading…</div>
      )}
      {state.kind === "anonymous" && (
        <Login onLoggedIn={(session) => setState({ kind: "signed-in", session })} />
      )}
      {state.kind === "signed-in" && <Workspace session={state.session} />}
    </div>
  );
}

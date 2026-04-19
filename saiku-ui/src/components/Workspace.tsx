import type { SaikuSession } from "../api/session";

interface Props {
  session: SaikuSession;
}

export function Workspace({ session }: Props) {
  return (
    <div className="workspace">
      <aside className="workspace__sidebar">
        <h2>Cubes</h2>
        <p>Datasource browser will live here once the query service is wired in.</p>
        <div className="placeholder">No datasources loaded</div>
      </aside>
      <main className="workspace__main">
        <h2>Welcome, {session.username}</h2>
        <p>Roles: {session.roles.join(", ")}</p>
        <div className="placeholder" style={{ marginTop: "var(--space-5)", minHeight: "360px" }}>
          Pivot grid (AG Grid) lands in the next Phase 4 slice.
        </div>
      </main>
    </div>
  );
}

import { useState, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { useT } from "../i18n";

/**
 * Authentication gate. On startup it checks `/auth/status`:
 * - auth not required (dev) or already authenticated → show the app;
 * - otherwise → login screen. The session cookie is httpOnly (handled by the backend),
 *   so no tokens are touched here: after login the status is invalidated and we re-enter.
 */
export function LoginGate({ children }: { children: ReactNode }) {
  const t = useT();
  const qc = useQueryClient();
  const status = useQuery({ queryKey: ["auth", "status"], queryFn: api.auth.status });

  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (status.isLoading) return <div className="page muted">{t("login.checkingAccess")}</div>;
  // If the status call fails, don't block the app (fail-open in dev/LAN).
  const needsLogin = status.data?.authRequired && !status.data.authenticated;
  if (!needsLogin) return <>{children}</>;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await api.auth.login(password);
      await qc.invalidateQueries(); // reload status and protected data
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="login-screen">
      <form className="login-box" onSubmit={submit}>
        <h1>{t("common.appName")}</h1>
        <p className="muted">{t("login.subtitle")}</p>
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder={t("login.passwordPlaceholder")}
          autoFocus
        />
        {error && <p className="error">{error}</p>}
        <button className="btn-primary" type="submit" disabled={submitting || password.length === 0}>
          {submitting ? t("login.signingIn") : t("login.signIn")}
        </button>
      </form>
    </div>
  );
}

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import type { AvailablePlugin } from "../types";
import { useT } from "../i18n";
import { useToast } from "../components/Toast";

/** Extension management: repositories, catalog, install/update/uninstall. */
export function ExtensionsPage() {
  const t = useT();
  const { show } = useToast();
  const qc = useQueryClient();
  const [repoUrl, setRepoUrl] = useState("");

  const repos = useQuery({ queryKey: ["extensions", "repos"], queryFn: api.extensions.repos });
  const catalog = useQuery({ queryKey: ["extensions", "catalog"], queryFn: api.extensions.catalog });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["extensions"] });
    qc.invalidateQueries({ queryKey: ["providers"] });
  };

  const addRepo = useMutation({
    mutationFn: (url: string) => api.extensions.addRepo(url),
    onSuccess: (ref) => {
      setRepoUrl("");
      show(t("extensions.repoAdded", { name: ref.name }));
      invalidate();
    },
    onError: (e) => show(e instanceof Error ? e.message : String(e)),
  });

  const removeRepo = useMutation({
    mutationFn: (url: string) => api.extensions.removeRepo(url),
    onSuccess: invalidate,
  });

  const install = useMutation({
    mutationFn: (name: string) => api.extensions.install(name),
    onSuccess: (res) => {
      if ("runtimeActive" in res) {
        show(
          res.runtimeActive
            ? t("extensions.installedActive", { name: res.extension.name })
            : res.message ?? t("extensions.installedPlain", { name: res.extension.name }),
        );
      }
      invalidate();
    },
    onError: (e) => show(e instanceof Error ? e.message : String(e)),
  });

  const update = useMutation({
    mutationFn: (name: string) => api.extensions.update(name),
    onSuccess: (res) => {
      show("upToDate" in res ? t("extensions.upToDate") : t("extensions.updated"));
      invalidate();
    },
  });

  const uninstall = useMutation({
    mutationFn: (name: string) => api.extensions.uninstall(name),
    onSuccess: invalidate,
  });

  const updateAll = useMutation({
    mutationFn: api.extensions.updateAll,
    onSuccess: (summary) => {
      show(
        t("extensions.updateAllResult", {
          updated: summary.updated.length,
          upToDate: summary.upToDate.length,
          failed: summary.failed.length,
        }),
      );
      invalidate();
    },
    onError: (e) => show(e instanceof Error ? e.message : String(e)),
  });

  const busy =
    addRepo.isPending ||
    install.isPending ||
    update.isPending ||
    uninstall.isPending ||
    updateAll.isPending;

  return (
    <>
      <h1>{t("extensions.title")}</h1>

      <div className="page-sections">
        <section>
          <h2 className="rail-title">{t("extensions.repository")}</h2>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (repoUrl.trim()) addRepo.mutate(repoUrl.trim());
            }}
            style={{ display: "flex", gap: 8, flexWrap: "wrap" }}
          >
            <input
              value={repoUrl}
              onChange={(e) => setRepoUrl(e.target.value)}
              placeholder={t("extensions.repoUrlPlaceholder")}
              style={{ flex: 1, minWidth: 260 }}
            />
            <button type="submit" disabled={addRepo.isPending}>
              {t("extensions.add")}
            </button>
          </form>
          <ul className="repo-list">
            {repos.data?.map((r) => (
              <li key={r.url}>
                <span>{r.name}</span>
                <button onClick={() => removeRepo.mutate(r.url)} disabled={busy}>
                  {t("extensions.remove")}
                </button>
              </li>
            ))}
            {repos.data?.length === 0 && (
              <li className="muted">{t("extensions.noRepositories")}</li>
            )}
          </ul>
        </section>

        <section>
          <div className="page-head">
            <h2 className="rail-title">{t("extensions.catalog")}</h2>
            {(repos.data?.length ?? 0) > 0 && (
              <button onClick={() => updateAll.mutate()} disabled={busy}>
                {t("extensions.updateAll")}
              </button>
            )}
          </div>
          {catalog.isLoading && <p className="muted">{t("extensions.loadingCatalog")}</p>}
          {catalog.isError && <p className="error">{String(catalog.error)}</p>}
          <div className="ext-grid">
            {catalog.data?.map((p) => (
              <ExtensionRow
                key={p.internalName}
                plugin={p}
                busy={busy}
                onInstall={() => install.mutate(p.internalName)}
                onUpdate={() => update.mutate(p.internalName)}
                onUninstall={() => uninstall.mutate(p.internalName)}
              />
            ))}
          </div>
        </section>
      </div>
    </>
  );
}

function ExtensionRow({
  plugin,
  busy,
  onInstall,
  onUpdate,
  onUninstall,
}: {
  plugin: AvailablePlugin;
  busy: boolean;
  onInstall: () => void;
  onUpdate: () => void;
  onUninstall: () => void;
}) {
  const t = useT();
  const installed = plugin.installedVersion != null;
  const updatable = installed && plugin.version > (plugin.installedVersion ?? 0);

  return (
    <div className="ext-row">
      <div className="ext-icon">
        {plugin.iconUrl ? <img src={plugin.iconUrl} alt="" loading="lazy" /> : null}
      </div>
      <div className="ext-info">
        <div className="ext-name">
          {plugin.name} <span className="muted">v{plugin.version}</span>
          {!plugin.runtimeSupported && (
            <span className="ext-badge" title={t("extensions.notExecutableTitle")}>
              {t("extensions.notExecutable")}
            </span>
          )}
          {installed && plugin.active === true && (
            <span className="ext-badge ext-badge-active">{t("extensions.statusActive")}</span>
          )}
          {installed && plugin.active === false && (
            <span
              className="ext-badge"
              title={
                plugin.activationError
                  ? t("extensions.statusInactiveTitle", { reason: plugin.activationError })
                  : undefined
              }
            >
              {t("extensions.statusInactive")}
            </span>
          )}
        </div>
        {plugin.description && <div className="muted ext-desc">{plugin.description}</div>}
        <div className="ext-meta muted">
          {plugin.sourceRepositories.length > 0 && `${plugin.sourceRepositories.join(", ")} · `}
          {plugin.language ?? "?"} · {plugin.tvTypes.join(", ")}
        </div>
      </div>
      <div className="ext-actions">
        {!installed && (
          <button onClick={onInstall} disabled={busy || !plugin.runtimeSupported}>
            {t("extensions.install")}
          </button>
        )}
        {updatable && (
          <button onClick={onUpdate} disabled={busy}>
            {t("extensions.update")}
          </button>
        )}
        {installed && (
          <button onClick={onUninstall} disabled={busy}>
            {t("extensions.uninstall")}
          </button>
        )}
      </div>
    </div>
  );
}

import {
  createContext,
  useContext,
  useState,
  type ReactNode,
} from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, getProfileId, setProfileId } from "../api/client";
import type { Profile } from "../types";
import { useLanguage, useT } from "../i18n";
import { useToast } from "../components/Toast";
import { profileGradient } from "../lib/colors";

interface ProfileContextValue {
  profile: Profile;
  /** Back to the picker (deselects the current profile). */
  switchProfile: () => void;
}

const ProfileContext = createContext<ProfileContextValue | null>(null);

export function useProfile(): ProfileContextValue {
  const ctx = useContext(ProfileContext);
  if (!ctx) throw new Error("useProfile outside ProfileGate");
  return ctx;
}

const PALETTE = ["#7c9cff", "#ff8a7c", "#7cd992", "#e6c07b", "#c792ea"];

/** null = no editor open. `profile: null` = creating a new one; otherwise editing that profile. */
interface EditorTarget {
  profile: Profile | null;
}

/**
 * Profile gate (multi-user): after the instance login, "Who's watching?".
 * The selection lives in localStorage (via client.ts) and travels as X-Profile-Id
 * on the /library calls. Switching profile → invalidates the library queries.
 */
export function ProfileGate({ children }: { children: ReactNode }) {
  const t = useT();
  const { show } = useToast();
  const { language, setLanguage } = useLanguage();
  const qc = useQueryClient();
  const [selectedId, setSelectedId] = useState<string | null>(getProfileId());
  const [manage, setManage] = useState(false);

  const [editorTarget, setEditorTarget] = useState<EditorTarget | null>(null);
  const [editorName, setEditorName] = useState("");
  const [editorColor, setEditorColor] = useState(PALETTE[0]);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const profiles = useQuery({ queryKey: ["profiles"], queryFn: api.profiles.list });

  function select(profile: Profile) {
    setProfileId(profile.id);
    setSelectedId(profile.id);
    qc.invalidateQueries({ queryKey: ["library"] });
  }

  function switchProfile() {
    setProfileId(null);
    setSelectedId(null);
    setManage(false);
    qc.invalidateQueries({ queryKey: ["library"] });
  }

  function openEditor(profile: Profile | null) {
    setEditorTarget({ profile });
    setEditorName(profile?.name ?? "");
    setEditorColor(profile?.color ?? PALETTE[0]);
    setConfirmDelete(false);
  }

  function closeEditor() {
    setEditorTarget(null);
    setConfirmDelete(false);
  }

  const saveMutation = useMutation({
    mutationFn: async () => {
      const name = editorName.trim();
      if (!name) throw new Error(t("profile.nameRequired"));
      return editorTarget?.profile
        ? api.profiles.update(editorTarget.profile.id, { name, color: editorColor })
        : api.profiles.create(name, editorColor);
    },
    onSuccess: async (profile) => {
      const wasNew = !editorTarget?.profile;
      await qc.invalidateQueries({ queryKey: ["profiles"] });
      closeEditor();
      show(t("profile.saved"));
      if (wasNew) select(profile);
    },
    onError: (e) => show(e instanceof Error ? e.message : String(e)),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.profiles.remove(id),
    onSuccess: async (_res, id) => {
      await qc.invalidateQueries({ queryKey: ["profiles"] });
      closeEditor();
      show(t("profile.deleted"));
      if (selectedId === id) switchProfile();
    },
  });

  function handleDeleteClick() {
    const id = editorTarget?.profile?.id;
    if (!id) return;
    if (!confirmDelete) {
      setConfirmDelete(true);
      return;
    }
    deleteMutation.mutate(id);
  }

  if (profiles.isLoading) return <div className="page muted">{t("profile.loading")}</div>;
  if (profiles.isError) return <div className="page error">{String(profiles.error)}</div>;

  const list = profiles.data ?? [];
  const selected = list.find((p) => p.id === selectedId) ?? null;

  if (!selected) {
    return (
      <div className="login-screen">
        <div className="profile-picker">
          <div className="profile-brand">
            <svg width="26" height="18" viewBox="0 0 34 24" aria-hidden="true">
              <circle cx="11" cy="14" r="8" fill="var(--accent-1)" />
              <circle cx="21" cy="11" r="9" fill="#3568e0" />
              <circle cx="27" cy="16" r="6" fill="var(--accent-2)" />
              <rect x="6" y="15" width="24" height="7" rx="3.5" fill="#3568e0" />
            </svg>
            <span className="profile-brand-text">{t("common.appName")}</span>
          </div>
          <h1>{t("profile.whoIsWatching")}</h1>
          <div className="profile-grid">
            {list.map((p) => (
              <button
                key={p.id}
                className="profile-tile"
                onClick={() => (manage ? openEditor(p) : select(p))}
              >
                <span className="profile-avatar" style={{ background: profileGradient(p.color) }}>
                  {p.name.slice(0, 1).toUpperCase()}
                  {manage && <span className="profile-edit-badge">✎</span>}
                </span>
                <span className="profile-name">{p.name}</span>
              </button>
            ))}
            <button className="profile-tile" onClick={() => openEditor(null)}>
              <span className="profile-avatar profile-avatar-add">+</span>
              <span className="profile-name">{t("profile.add")}</span>
            </button>
          </div>
          {list.length === 0 && <p className="muted">{t("profile.empty")}</p>}
          <button
            className={manage ? "profile-manage-toggle active" : "profile-manage-toggle"}
            onClick={() => setManage((m) => !m)}
          >
            {manage ? t("profile.done") : t("profile.manage")}
          </button>
          <div className="language-switch">
            <button
              type="button"
              className={language === "en" ? "language-switch-active" : ""}
              onClick={() => setLanguage("en")}
            >
              English
            </button>
            <span className="muted">·</span>
            <button
              type="button"
              className={language === "it" ? "language-switch-active" : ""}
              onClick={() => setLanguage("it")}
            >
              Italiano
            </button>
          </div>
        </div>

        {editorTarget && (
          <div className="modal-overlay" onClick={closeEditor}>
            <div className="modal-card" onClick={(e) => e.stopPropagation()}>
              <h2>{editorTarget.profile ? t("profile.editProfile") : t("profile.newProfile")}</h2>
              <div className="profile-editor-preview">
                <span className="profile-avatar" style={{ background: profileGradient(editorColor) }}>
                  {(editorName[0] ?? "?").toUpperCase()}
                </span>
                <input
                  value={editorName}
                  onChange={(e) => setEditorName(e.target.value)}
                  placeholder={t("profile.newProfilePlaceholder")}
                  maxLength={20}
                  autoFocus
                />
              </div>
              <div className="profile-editor-palette">
                <span className="muted profile-editor-palette-label">{t("profile.color")}</span>
                <div className="profile-editor-swatches">
                  {PALETTE.map((c) => (
                    <button
                      key={c}
                      type="button"
                      className={c === editorColor ? "profile-swatch profile-swatch-active" : "profile-swatch"}
                      style={{ background: c }}
                      onClick={() => setEditorColor(c)}
                    />
                  ))}
                </div>
              </div>
              <div className="profile-editor-actions">
                {editorTarget.profile && list.length > 1 && (
                  <button
                    type="button"
                    className="profile-delete-btn"
                    onClick={handleDeleteClick}
                    disabled={deleteMutation.isPending}
                  >
                    {confirmDelete ? t("profile.confirmDelete") : t("profile.delete")}
                  </button>
                )}
                <button type="button" onClick={closeEditor} disabled={saveMutation.isPending || deleteMutation.isPending}>
                  {t("profile.cancel")}
                </button>
                <button
                  type="button"
                  className="profile-save-btn"
                  onClick={() => saveMutation.mutate()}
                  disabled={saveMutation.isPending || !editorName.trim()}
                >
                  {t("profile.save")}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }

  return (
    <ProfileContext.Provider value={{ profile: selected, switchProfile }}>
      {children}
    </ProfileContext.Provider>
  );
}

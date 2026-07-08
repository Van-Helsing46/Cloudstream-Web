import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, getProfileId, setProfileId } from "../api/client";
import type { Profile } from "../types";
import { useLanguage, useT } from "../i18n";
import { useToast } from "../components/Toast";
import { ProfileAvatar, profileAvatarStyle } from "../components/ProfileAvatar";
import { PRESET_AVATARS, presetAvatarSrc } from "../lib/avatars";
import { profileGradient } from "../lib/colors";

const AVATAR_MAX_BYTES = 2 * 1024 * 1024;
const AVATAR_TYPES = ["image/png", "image/jpeg", "image/webp"];

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
  const [editorAvatar, setEditorAvatar] = useState<string | null>(null);
  const [pendingAvatarFile, setPendingAvatarFile] = useState<File | null>(null);
  const [pendingAvatarPreview, setPendingAvatarPreview] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  // Revokes the object URL used to preview a not-yet-uploaded avatar file.
  useEffect(() => {
    return () => {
      if (pendingAvatarPreview) URL.revokeObjectURL(pendingAvatarPreview);
    };
  }, [pendingAvatarPreview]);

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
    setEditorAvatar(profile?.avatar ?? null);
    setPendingAvatarFile(null);
    setPendingAvatarPreview(null);
    setConfirmDelete(false);
  }

  function closeEditor() {
    setEditorTarget(null);
    setPendingAvatarFile(null);
    setPendingAvatarPreview(null);
    setConfirmDelete(false);
  }

  function pickAvatarFile(file: File | undefined) {
    if (!file) return;
    if (!AVATAR_TYPES.includes(file.type)) {
      show(t("profile.avatarInvalidType"));
      return;
    }
    if (file.size > AVATAR_MAX_BYTES) {
      show(t("profile.avatarTooLarge"));
      return;
    }
    setEditorAvatar("upload");
    setPendingAvatarFile(file);
    setPendingAvatarPreview(URL.createObjectURL(file));
  }

  const saveMutation = useMutation({
    mutationFn: async () => {
      const name = editorName.trim();
      if (!name) throw new Error(t("profile.nameRequired"));
      const clearingUpload = editorAvatar === null && editorTarget?.profile?.avatar === "upload";
      // Uploads and file deletions are handled by their own endpoints; a plain avatar
      // string change (or leaving it untouched) goes through the regular profile update.
      const avatar = clearingUpload ? editorTarget!.profile!.avatar! : (editorAvatar ?? "");
      const profile = editorTarget?.profile
        ? await api.profiles.update(editorTarget.profile.id, { name, color: editorColor, avatar })
        : await api.profiles.create(name, editorColor, avatar || undefined);
      if (pendingAvatarFile) return api.profiles.uploadAvatar(profile.id, pendingAvatarFile);
      if (clearingUpload) return api.profiles.deleteAvatar(profile.id);
      return profile;
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

  const editorPreviewSrc =
    pendingAvatarPreview ??
    (editorAvatar
      ? (presetAvatarSrc(editorAvatar) ??
        (editorAvatar === "upload" && editorTarget?.profile
          ? api.profiles.avatarUrl(editorTarget.profile.id)
          : undefined))
      : undefined);

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
                <span className="profile-avatar" style={profileAvatarStyle(p)}>
                  <ProfileAvatar profile={p} />
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
                <span
                  className="profile-avatar"
                  style={editorPreviewSrc ? {} : { background: profileGradient(editorColor) }}
                >
                  {editorPreviewSrc ? (
                    <img className="profile-avatar-img" src={editorPreviewSrc} alt="" />
                  ) : (
                    (editorName[0] ?? "?").toUpperCase()
                  )}
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
              <div className="profile-editor-avatars">
                <span className="muted profile-editor-palette-label">{t("profile.avatar")}</span>
                <div className="profile-editor-avatar-grid">
                  <button
                    type="button"
                    className={
                      editorAvatar === null
                        ? "profile-avatar-none-btn profile-avatar-none-btn-active"
                        : "profile-avatar-none-btn"
                    }
                    onClick={() => {
                      setEditorAvatar(null);
                      setPendingAvatarFile(null);
                      setPendingAvatarPreview(null);
                    }}
                    title={t("profile.avatarNone")}
                  >
                    {t("profile.avatarNone")}
                  </button>
                  {PRESET_AVATARS.map((preset) => (
                    <button
                      key={preset.id}
                      type="button"
                      className={
                        editorAvatar === `preset:${preset.id}`
                          ? "profile-avatar-preset profile-avatar-preset-active"
                          : "profile-avatar-preset"
                      }
                      onClick={() => {
                        setEditorAvatar(`preset:${preset.id}`);
                        setPendingAvatarFile(null);
                        setPendingAvatarPreview(null);
                      }}
                    >
                      <img src={preset.src} alt="" />
                    </button>
                  ))}
                </div>
                <label className="profile-avatar-upload">
                  {t("profile.avatarUpload")}
                  <input
                    type="file"
                    accept={AVATAR_TYPES.join(",")}
                    onChange={(e) => pickAvatarFile(e.target.files?.[0])}
                  />
                </label>
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

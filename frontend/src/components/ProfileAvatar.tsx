import type { CSSProperties } from "react";
import { api } from "../api/client";
import { presetAvatarSrc } from "../lib/avatars";
import { profileGradient } from "../lib/colors";

type AvatarLike = { id: string; name: string; color: string; avatar?: string | null };

/** Background style for the avatar container: only the initial+color fallback needs the gradient. */
export function profileAvatarStyle(profile: AvatarLike): CSSProperties {
  if (profile.avatar) return {};
  return { background: profileGradient(profile.color) };
}

/**
 * Avatar content: a preset image, the uploaded image, or the initial+gradient fallback.
 * Renders inside whatever container the caller uses (span, button, ...) — combine with
 * `profileAvatarStyle` for the background.
 */
export function ProfileAvatar({ profile }: { profile: AvatarLike }) {
  if (profile.avatar === "upload") {
    return <img className="profile-avatar-img" src={api.profiles.avatarUrl(profile.id)} alt="" />;
  }
  const presetSrc = profile.avatar ? presetAvatarSrc(profile.avatar) : undefined;
  if (presetSrc) {
    return <img className="profile-avatar-img" src={presetSrc} alt="" />;
  }
  return <>{profile.name.slice(0, 1).toUpperCase()}</>;
}

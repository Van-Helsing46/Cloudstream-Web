/** Built-in avatar images (original inline SVGs, no third-party assets), selectable in the profile editor. */
export interface PresetAvatar {
  id: string;
  src: string;
}

export const PRESET_AVATARS: PresetAvatar[] = [
  { id: "1", src: "/avatars/1.svg" },
  { id: "2", src: "/avatars/2.svg" },
  { id: "3", src: "/avatars/3.svg" },
  { id: "4", src: "/avatars/4.svg" },
  { id: "5", src: "/avatars/5.svg" },
  { id: "6", src: "/avatars/6.svg" },
  { id: "7", src: "/avatars/7.svg" },
  { id: "8", src: "/avatars/8.svg" },
];

export function presetAvatarSrc(avatar: string): string | undefined {
  if (!avatar.startsWith("preset:")) return undefined;
  return PRESET_AVATARS.find((p) => p.id === avatar.slice("preset:".length))?.src;
}

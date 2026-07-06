/** Deterministic string hash → hue (0-359), used to derive fallback gradients. */
function hashHue(seed: string): number {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  }
  return hash % 360;
}

/** Fallback poster background for items without a `posterUrl`, derived from their title. */
export function posterGradient(seed: string): string {
  const hue = hashHue(seed);
  return `linear-gradient(160deg, hsl(${hue} 45% 42%) 0%, hsl(${(hue + 40) % 360} 35% 18%) 100%)`;
}

function darken(hex: string, factor: number): string {
  const n = hex.replace("#", "");
  const channel = (offset: number) =>
    Math.round(parseInt(n.slice(offset, offset + 2), 16) * factor)
      .toString(16)
      .padStart(2, "0");
  return `#${channel(0)}${channel(2)}${channel(4)}`;
}

/** Two-tone gradient for a profile avatar, derived from its stored solid color. */
export function profileGradient(hex: string): string {
  return `linear-gradient(135deg, ${hex}, ${darken(hex, 0.6)})`;
}

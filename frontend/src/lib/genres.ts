/**
 * v1 genre navigation: providers don't expose a genre taxonomy on their home
 * sections, so genres are inferred from section titles via keyword matching
 * (IT/EN). Coverage depends on how each provider names its sections — titles
 * that don't match any pattern simply don't appear under any genre.
 */
export type GenreKey =
  | "action"
  | "comedy"
  | "drama"
  | "horror"
  | "scifi"
  | "fantasy"
  | "animation"
  | "thriller"
  | "documentary"
  | "romance"
  | "kids";

// Word-bounded so e.g. "Animazione" (which contains the substring "azione")
// doesn't get misread as the "action" genre — checked before "animation" below.
const GENRE_PATTERNS: { key: GenreKey; pattern: RegExp }[] = [
  { key: "animation", pattern: /\b(animazione|anime|cartoni|animation)\b/i },
  { key: "action", pattern: /\b(azione|action)\b/i },
  { key: "comedy", pattern: /\b(comm?edi\w*|comedy)\b/i },
  { key: "drama", pattern: /\b(dramma\w*|drama)\b/i },
  { key: "horror", pattern: /\b(horror|terror\w*)\b/i },
  { key: "scifi", pattern: /\b(fantascienza|sci-?fi)\b/i },
  { key: "fantasy", pattern: /\b(fantasy|fantastic\w*)\b/i },
  { key: "thriller", pattern: /\b(thriller|crime|poliziesc\w*)\b/i },
  { key: "documentary", pattern: /\b(documentari\w*|docu\w*)\b/i },
  { key: "romance", pattern: /\b(romantic\w*|sentimental\w*|romance)\b/i },
  { key: "kids", pattern: /\b(bambini|famiglia|kids|family)\b/i },
];

/** First genre whose keywords appear in the section title, or null if none match. */
export function matchGenre(sectionTitle: string): GenreKey | null {
  for (const { key, pattern } of GENRE_PATTERNS) {
    if (pattern.test(sectionTitle)) return key;
  }
  return null;
}

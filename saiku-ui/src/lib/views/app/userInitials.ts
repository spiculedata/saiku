/*
 * Initials for the rail's user disc.
 *
 * The disc used to render whatever literal string the app author typed into
 * "Avatar initials" — FoodMart Ops shipped "RM", so every viewer of that app
 * was greeted as the same fictional regional manager. A user disc that doesn't
 * track the signed-in user isn't an avatar, it's decoration.
 *
 * Pure so the rule is testable without a session or a DOM.
 */

/** Max glyphs on the disc — two is what fits the circle at its rail size. */
const MAX = 2;

/** Split on the separators real usernames use: spaces, dots, underscores,
 *  hyphens and the local/domain break in an email. */
const SEPARATORS = /[\s._\-@]+/;

/**
 * Derive display initials from a username or display name.
 *
 *   "Tom Barber"       -> "TB"
 *   "tom.barber"       -> "TB"
 *   "tom@spicule.co.uk"-> "TS"
 *   "admin"            -> "AD"   (single word: first two letters)
 *   ""                 -> ""     (caller hides the disc)
 *
 * Non-letter leading characters are skipped so "1st.user" doesn't render "1U".
 */
export function userInitials(username: string | null | undefined): string {
  if (!username) return "";
  const parts = username
    .split(SEPARATORS)
    .map((p) => p.replace(/[^\p{L}\p{N}]/gu, ""))
    .filter((p) => p.length > 0);
  if (parts.length === 0) return "";

  if (parts.length === 1) {
    // One word carries no second initial, so take its first two letters —
    // "AD" reads as a monogram where a bare "A" reads as a mistake.
    return parts[0].slice(0, MAX).toUpperCase();
  }
  return parts
    .slice(0, MAX)
    .map((p) => p[0])
    .join("")
    .toUpperCase();
}

/** How the rail's user disc gets its label. */
export type AvatarSource = "user" | "fixed";

/**
 * Resolve what the disc should show.
 *
 * Back-compatible by construction: an app that predates {@link AvatarSource}
 * carries only a literal `avatar`, and an absent source is treated as "fixed"
 * so its disc keeps rendering exactly what it rendered before. New apps set
 * `avatarSource: "user"` and follow the signed-in user.
 *
 * Returns "" when there's nothing to show — the caller hides the disc.
 */
export function resolveAvatar(
  footer: { avatar?: string; avatarSource?: AvatarSource } | null | undefined,
  username: string | null | undefined,
): string {
  if (!footer) return "";
  const source: AvatarSource = footer.avatarSource ?? "fixed";
  if (source === "user") return userInitials(username);
  return (footer.avatar ?? "").trim();
}

/** Small, dependency-free helpers shared across the app. */

/** Conditional className joiner. */
export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}

/** Stable, collision-resistant id for client-side records. */
export function createId(prefix: string): string {
  const uuid =
    globalThis.crypto?.randomUUID?.() ??
    `${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
  return `${prefix}_${uuid}`;
}

/** Clamp `n` into the inclusive range [min, max]. */
export function clamp(n: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, n));
}

/** Zero-pad a number to `width` digits. Used for the promo-code NN/DD segments. */
export function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

/** A → 0, B → 1, … J → 9. Returns -1 for anything else. */
export function tierIndex(tier: string): number {
  const code = tier.toUpperCase().charCodeAt(0);
  return code >= 65 && code <= 74 ? code - 65 : -1;
}

/** 0 → 'A', 9 → 'J'. */
export function tierFromIndex(index: number): string {
  return String.fromCharCode(65 + index);
}

/** Case-insensitive substring match, tolerant of empty needles. */
export function matches(haystack: string | undefined | null, needle: string): boolean {
  if (!needle.trim()) return true;
  return (haystack ?? '').toLowerCase().includes(needle.trim().toLowerCase());
}
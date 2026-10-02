/**
 * Date and currency formatting.
 *
 * Timezone policy: campaign start/end are *date-only* values (`YYYY-MM-DD`) that represent
 * a local calendar day in the restaurant's city. They are never round-tripped through
 * `new Date('YYYY-MM-DD')`, which JavaScript parses as UTC and can shift by a day in
 * negative-offset zones. Parse them as local midnight instead.
 */

const MONTHS = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
] as const;

/** Parse `YYYY-MM-DD` as *local* midnight (not UTC). */
export function parseLocalDate(isoDate: string): Date {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, (m ?? 1) - 1, d ?? 1);
}

/** Today's local calendar day as `YYYY-MM-DD`. */
export function toDateKey(date: Date = new Date()): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/**
 * Calendar month key for a restaurant, in local time: `YYYY-MM`.
 *
 * Exclusive promo tiers (E–J) are scoped per restaurant per calendar month, so this
 * must be computed from local components — never from a UTC ISO string, or a campaign
 * issued at 00:30 IST on the 1st could be filed under the previous month.
 */
export function monthKey(restaurantId: string, date: Date = new Date()): string {
  return `${restaurantId}:${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

/** `15 Oct` / `15 Oct 2025` — omit the year when it is the current one. */
export function formatDate(isoDate: string, opts?: { withYear?: boolean }): string {
  const d = parseLocalDate(isoDate);
  const base = `${d.getDate()} ${MONTHS[d.getMonth()]}`;
  const showYear = opts?.withYear ?? d.getFullYear() !== new Date().getFullYear();
  return showYear ? `${base} ${d.getFullYear()}` : base;
}

/** `15 Oct – 22 Oct 2025` */
export function formatDateRange(startIso: string, endIso: string): string {
  const start = parseLocalDate(startIso);
  const end = parseLocalDate(endIso);
  const sameYear = start.getFullYear() === end.getFullYear();
  const left = `${start.getDate()} ${MONTHS[start.getMonth()]}`;
  const right = `${end.getDate()} ${MONTHS[end.getMonth()]}${sameYear ? '' : ` ${end.getFullYear()}`}`;
  return `${left} – ${right}${sameYear ? ` ${end.getFullYear()}` : ''}`;
}

/** `12:44 PM` */
export function formatTime(isoDateTime: string): string {
  const d = new Date(isoDateTime);
  let h = d.getHours();
  const m = String(d.getMinutes()).padStart(2, '0');
  const suffix = h >= 12 ? 'PM' : 'AM';
  h = h % 12 || 12;
  return `${h}:${m} ${suffix}`;
}

/** `4m ago`, `2h ago`, `3d ago`, `12 Mar` — for activity feeds and telemetry. */
export function formatRelative(isoDateTime: string, from: Date = new Date()): string {
  const diffMs = from.getTime() - new Date(isoDateTime).getTime();
  const mins = Math.floor(diffMs / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return formatDate(isoDateTime.slice(0, 10));
}

/** Whole days from today to `isoDate`; negative when in the past. */
export function daysFromToday(isoDate: string, from: Date = new Date()): number {
  const target = parseLocalDate(isoDate);
  const start = new Date(from.getFullYear(), from.getMonth(), from.getDate());
  return Math.round((target.getTime() - start.getTime()) / 86_400_000);
}

// ── Currency ───────────────────────────────────────────────────────────
// All amounts are integer paise. Division happens only at render time.

/** `₹1,940` — Indian digit grouping (lakh/crore). */
export function formatINR(paise: number): string {
  const rupees = Math.round(paise / 100);
  return `₹${rupees.toLocaleString('en-IN', { maximumFractionDigits: 0 })}`;
}

/** `₹1.9L` / `₹18.4L` / `₹3.2Cr` — for KPI tiles, matching the Stitch dashboard. */
export function formatCompactINR(paise: number): string {
  const rupees = paise / 100;
  if (rupees >= 10_000_000) return `₹${(rupees / 10_000_000).toFixed(1)}Cr`;
  if (rupees >= 100_000) return `₹${(rupees / 100_000).toFixed(1)}L`;
  if (rupees >= 1_000) return `₹${(rupees / 1_000).toFixed(1)}k`;
  return `₹${Math.round(rupees)}`;
}

/** `14,820` */
export function formatCount(n: number): string {
  return n.toLocaleString('en-IN');
}

/** `+34.8%` */
export function formatPercent(value: number, digits = 1): string {
  return `${value.toFixed(digits)}%`;
}
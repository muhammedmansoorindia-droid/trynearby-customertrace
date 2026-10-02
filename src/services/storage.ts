/**
 * Versioned LocalStorage adapter for the demo dataset.
 *
 * The app is frontend-only: LocalStorage stands in for a database. Everything is
 * namespaced under `ct:v1:` so the schema version travels with the data, letting a future
 * migration run on load rather than after a user has already hit a render error.
 *
 * Every access is defensive — Safari private mode, a full quota, or a corrupted value must
 * degrade to "no saved data" (and therefore the seed set) rather than crash the app.
 */

export const STORAGE_PREFIX = 'ct:v1:';
export const DB_KEY = `${STORAGE_PREFIX}db`;
export const SESSION_KEY = `${STORAGE_PREFIX}session`;

/** Bump when the persisted shape changes; `migrate` then upgrades older payloads. */
export const SCHEMA_VERSION = 1;

type Migration = (data: Record<string, unknown>) => Record<string, unknown>;

const MIGRATIONS: Record<number, Migration> = {
  // v1 is the first release — no migrations yet.
};

function isBrowser(): boolean {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined';
}

export function readJson<T>(key: string, fallback: T): T {
  if (!isBrowser()) return fallback;
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw) as T & { __v?: number };
    if (parsed && typeof parsed === 'object' && typeof parsed.__v === 'number') {
      let current = parsed.__v;
      let data = parsed as unknown as Record<string, unknown>;
      while (current < SCHEMA_VERSION) {
        const migrate = MIGRATIONS[current + 1];
        if (!migrate) break;
        data = migrate(data);
        current += 1;
      }
      return data as T;
    }
    return parsed;
  } catch (error) {
    console.warn(`[storage] Failed to read "${key}"; falling back to defaults.`, error);
    return fallback;
  }
}

export function writeJson(key: string, value: unknown): boolean {
  if (!isBrowser()) return false;
  try {
    window.localStorage.setItem(key, JSON.stringify({ ...(value as object), __v: SCHEMA_VERSION }));
    return true;
  } catch (error) {
    // Most likely QuotaExceededError. The app keeps working in-memory.
    console.warn(`[storage] Failed to write "${key}".`, error);
    return false;
  }
}

export function remove(key: string): void {
  if (!isBrowser()) return;
  try {
    window.localStorage.removeItem(key);
  } catch (error) {
    console.warn(`[storage] Failed to remove "${key}".`, error);
  }
}

/** Wipe every namespaced key. Backs the "Reset demo data" control. */
export function clearAll(): void {
  if (!isBrowser()) return;
  try {
    const doomed: string[] = [];
    for (let i = 0; i < window.localStorage.length; i += 1) {
      const key = window.localStorage.key(i);
      if (key?.startsWith(STORAGE_PREFIX)) doomed.push(key);
    }
    doomed.forEach((key) => window.localStorage.removeItem(key));
  } catch (error) {
    console.warn('[storage] Failed to clear namespace.', error);
  }
}

/**
 * Rehydrate when another tab writes.
 *
 * Without this, two open tabs on the same dataset diverge silently — approving a campaign in
 * one tab would leave the other showing stale state.
 */
export function subscribeToExternalChanges(onChange: (key: string) => void): () => void {
  if (!isBrowser()) return () => {};
  const handler = (event: StorageEvent) => {
    if (event.key?.startsWith(STORAGE_PREFIX)) onChange(event.key);
  };
  window.addEventListener('storage', handler);
  return () => window.removeEventListener('storage', handler);
}
/**
 * Application store.
 *
 * One in-memory `Database` is the single source of truth for all three portals. Because
 * there is no API boundary, an approval in Admin and the resulting state change in Creator
 * are literally the same object — the two views can never drift apart, which is the whole
 * point of the demo.
 *
 * LocalStorage is a *cache*, not the source of truth: it is read once on boot and written
 * back on every mutation so a refresh (or a second tab) resumes where the user left off.
 * "Reset demo data" clears the namespace and regenerates the seed.
 */

import { create } from 'zustand';

import { createSeedDatabase, type Database } from '@/data/seed';
import {
  applyTransition,
  checkTransition,
  type CampaignAction,
} from '@/services/campaignWorkflow';
import { generatePromoCode } from '@/services/promoCode';
import {
  DB_KEY,
  SESSION_KEY,
  clearAll,
  readJson,
  writeJson,
} from '@/services/storage';
import type {
  Campaign,
  Portal,
  PromoCodeRecord,
  Redemption,
  ScanEvent,
  Session,
} from '@/types/models';
import { createId } from '@/utils';
import { monthKey, toDateKey } from '@/utils/format';

const DEFAULT_SESSION: Session = { portal: null };

export interface AppState {
  db: Database;
  session: Session;
  /** Set when the last action was refused, so the UI can explain why. */
  lastError: string | null;

  setPortal: (
    portal: Portal,
    options?: { restaurantId?: string; creatorId?: string },
  ) => void;
  signOut: () => void;
  resetDemo: () => void;
  clearError: () => void;
  /**
   * Replace the whole dataset.
   *
   * Reserved for the campaign wizard, which needs to add a campaign *and* its promo-code
   * ledger entry in one atomic write. Narrow, named setters are preferred everywhere else so
   * a component cannot quietly drop a collection.
   */
  setDb: (db: Database) => void;

  performAction: (campaignId: string, action: CampaignAction, reason?: string) => boolean;
  recordScan: (scan: Omit<ScanEvent, 'id'>) => void;
  recordRedemption: (redemption: Omit<Redemption, 'id'>) => void;
  markNotificationRead: (notificationId: string) => void;
  confirmBooking: (bookingId: string) => void;
}

/** Persist both slices; a failure is logged by the storage adapter, never thrown. */
function persist(db: Database, session: Session): void {
  writeJson(DB_KEY, db);
  writeJson(SESSION_KEY, session);
}

function loadInitial(): { db: Database; session: Session } {
  const stored = readJson<Database | null>(DB_KEY, null);
  const session = readJson<Session>(SESSION_KEY, DEFAULT_SESSION);
  // A stored payload missing core collections is treated as absent rather than trusted.
  const usable = stored && Array.isArray(stored.campaigns) && Array.isArray(stored.redemptions);
  return { db: usable ? stored : createSeedDatabase(), session };
}


export const useAppStore = create<AppState>((set, get) => ({
  ...loadInitial(),
  lastError: null,

  setPortal: (portal, options) => {
    const session: Session = { portal };
    if (options?.restaurantId) session.restaurantId = options.restaurantId;
    if (options?.creatorId) session.creatorId = options.creatorId;
    const { db } = get();
    set({ session });
    persist(db, session);
  },

  signOut: () => {
    const { db } = get();
    const session: Session = { portal: null };
    set({ session, lastError: null });
    persist(db, session);
  },

  resetDemo: () => {
    clearAll();
    const db = createSeedDatabase();
    const session: Session = { portal: null };
    set({ db, session, lastError: null });
    persist(db, session);
  },

  clearError: () => set({ lastError: null }),

  recordScan: (scan) => {
    const { db, session } = get();
    const next: Database = { ...db, scans: [...db.scans, { ...scan, id: createId('scn') }] };
    set({ db: next });
    persist(next, session);
  },

  setDb: (db) => {
    const { session } = get();
    set({ db });
    persist(db, session);
  },

  performAction: (campaignId, action, reason) => {
    const { db, session } = get();
    const campaign = db.campaigns.find((c) => c.id === campaignId);
    if (!campaign) {
      set({ lastError: 'That campaign no longer exists.' });
      return false;
    }

    // The transition table is the authority — an illegal move is refused here rather than
    // silently mutating state, so the UI can show the reason inline.
    const check = checkTransition(campaign.status, action);
    if (!check.allowed) {
      set({ lastError: check.reason ?? 'That action is not allowed right now.' });
      return false;
    }

    const now = new Date().toISOString();
    let updated: Campaign = applyTransition(campaign, action, now);
    if (action === 'reject') {
      updated = { ...updated, rejectionReason: reason?.trim() || 'No reason supplied.' };
    }

    const next: Database = {
      ...db,
      campaigns: db.campaigns.map((c) => (c.id === campaignId ? updated : c)),
    };
    set({ db: next, lastError: null });
    persist(next, session);
    return true;
  },

  recordRedemption: (redemption) => {
    const { db, session } = get();
    const campaign = db.campaigns.find((c) => c.id === redemption.campaignId);
    if (!campaign) {
      set({ lastError: 'Unknown campaign for that redemption.' });
      return;
    }

    // The customer cap is encoded in the promo code, so it is enforced at the write path too
    // — two open tabs must not be able to overshoot it between renders.
    const distinct = new Set(
      db.redemptions
        .filter((r) => r.campaignId === campaign.id && r.customerRef)
        .map((r) => r.customerRef as string),
    );
    if (redemption.customerRef) distinct.add(redemption.customerRef);
    if (distinct.size > campaign.targetCustomers) {
      set({
        lastError: `This campaign has already reached its ${campaign.targetCustomers}-customer cap.`,
      });
      return;
    }

    const next: Database = {
      ...db,
      redemptions: [...db.redemptions, { ...redemption, id: createId('rdm') }],
    };
    set({ db: next, lastError: null });
    persist(next, session);
  },

  markNotificationRead: (notificationId) => {
    const { db, session } = get();
    const next: Database = {
      ...db,
      notifications: db.notifications.map((n) =>
        n.id === notificationId ? { ...n, read: true } : n,
      ),
    };
    set({ db: next });
    persist(next, session);
  },

  confirmBooking: (bookingId) => {
    const { db, session } = get();
    const next: Database = {
      ...db,
      bookings: db.bookings.map((b) =>
        b.id === bookingId ? { ...b, status: 'confirmed' as const } : b,
      ),
    };
    set({ db: next });
    persist(next, session);
  },
}));

/**
 * Mint a promo code for a new or resubmitted campaign.
 *
 * Lives beside the store rather than in a component so the same ledger rules apply no matter
 * which screen triggered the draft. Returns the generated `record` as well as the code — the
 * caller must persist both, or the exclusivity ledger would forget the tier was spent and
 * hand the same exclusive tier out twice in a month.
 */
export function mintCodeFor(
  db: Database,
  restaurantId: string,
  campaignId: string,
  targetCustomers: number,
  durationDays: number,
  now: Date = new Date(),
): PromoCodeRecord | null {
  const result = generatePromoCode(
    { restaurantId, campaignId, targetCustomers, durationDays, now },
    { records: db.promoCodes },
  );
  return result.ok ? result.value.record : null;
}

/** Calendar month key for a restaurant — the scope of exclusive tier consumption. */
export function currentMonthKey(restaurantId: string, now: Date = new Date()): string {
  return monthKey(restaurantId, now);
}

/** Today as a local `YYYY-MM-DD`, for seeding new records. */
export function today(now: Date = new Date()): string {
  return toDateKey(now);
}

/**
 * Customer Trace — shared domain models.
 *
 * These interfaces are the single source of truth for all three portals. Because the Admin,
 * Restaurant and Creator portals are route groups inside one SPA reading one store, an approval
 * in Admin and the resulting state change in Creator are the *same object* — there is no API
 * boundary and therefore no possibility of the two drifting apart.
 *
 * Money is stored as integer paise everywhere to avoid floating-point drift.
 * Dates are ISO 8601 strings; campaign start/end are date-only (`YYYY-MM-DD`).
 */

export type Portal = 'admin' | 'restaurant' | 'creator';

/** Who the user is acting as. No real auth in the demo — chosen at the role screen. */
export interface Session {
  portal: Portal | null;
  restaurantId?: string;
  creatorId?: string;
}

// ── Campaign ──────────────────────────────────────────────────────────

export type CampaignStatus =
  | 'draft'
  | 'pending_admin'
  | 'approved'
  | 'rejected'
  | 'live'
  | 'completed'
  | 'cancelled';

export interface Campaign {
  id: string;
  creatorId: string;
  restaurantId: string;
  title: string;
  offerText: string;
  discountPercent: number;
  minBillAmountInPaise?: number;

  /**
   * Customer target for this campaign.
   *
   * Constrained to 10–99 because it is encoded as the two `NN` characters of the promo
   * code (format `LLNNDDX`). A target of 150 is *not representable*; see
   * `services/promoCode.ts` → `MIN_CUSTOMER_TARGET` / `MAX_CUSTOMER_TARGET`.
   */
  targetCustomers: number;

  /** Campaign duration in days. Encoded as the two `DD` characters; valid 1–31. */
  durationDays: number;

  /** Date-only ISO (`YYYY-MM-DD`). */
  startDate: string;
  /** Date-only ISO (`YYYY-MM-DD`). */
  endDate: string;

  status: CampaignStatus;

  /** Assigned at draft→pending_admin. Format `LLNNDDX`. */
  promoCode?: string;

  submittedAt?: string;
  decidedAt?: string;
  decidedByAdminId?: string;
  rejectionReason?: string;

  createdAt: string;
}

// ── Booking ───────────────────────────────────────────────────────────

export type BookingStatus = 'requested' | 'confirmed' | 'completed' | 'cancelled';

export interface Booking {
  id: string;
  creatorId: string;
  restaurantId: string;
  campaignId?: string;
  date: string;
  partySize: number;
  status: BookingStatus;
  note?: string;
  createdAt: string;
}

// ── Tracking — the critical distinction ────────────────────────────────

export type ScanMethod = 'qr' | 'promo_manual';

/** Why a scan was rejected. Mirrors the validation ladder in `services/promoCode.ts`. */
export type PromoRejectionReason =
  | 'INVALID_FORMAT'
  | 'INVALID_CUSTOMER_TARGET'
  | 'INVALID_DURATION'
  | 'INVALID_TIER'
  | 'UNKNOWN_CODE'
  | 'CODE_CAMPAIGN_MISMATCH'
  | 'CAMPAIGN_NOT_LIVE'
  | 'NOT_STARTED'
  | 'EXPIRED'
  | 'QUOTA_EXHAUSTED'
  | 'TIER_MONTHLY_LIMIT'
  | 'ALREADY_REDEEMED';

/**
 * A customer (or staff member) presented a QR pass, or a code was typed manually.
 *
 * IMPORTANT: a scan records *interest* only. It never counts as a verified customer.
 * Verified customers are derived exclusively from `Redemption`.
 */
export interface ScanEvent {
  id: string;
  campaignId: string;
  restaurantId: string;
  creatorId: string;
  method: ScanMethod;
  promoCode: string;
  scannedAt: string;
  /** Anonymous identifier. `null` when the diner cannot be identified. */
  customerRef: string | null;
  result: 'valid' | 'invalid';
  rejectionReason?: PromoRejectionReason;
  deviceLabel?: string;
}

/**
 * Staff confirmed a discount at bill settlement.
 *
 * This is the only record that produces a verified customer or a discount amount.
 * `scanEventId` is optional: a redemption may occur with no preceding scan (manual entry),
 * and a scan may produce zero or one redemption.
 */
export interface Redemption {
  id: string;
  campaignId: string;
  restaurantId: string;
  creatorId: string;
  promoCode: string;
  scanEventId: string | null;
  customerRef: string | null;
  redeemedAt: string;
  billAmountInPaise: number;
  discountAmountInPaise: number;
  channel: 'dine_in' | 'takeaway' | 'delivery';
  tableOrChannelRef?: string;
  staffName?: string;
  /** Date-only ISO. Component of the duplicate-redemption key. */
  visitDate: string;
}

// ── Notifications ─────────────────────────────────────────────────────

export type NotificationType =
  | 'campaign_submitted'
  | 'campaign_approved'
  | 'campaign_rejected'
  | 'campaign_request_received'
  | 'booking_received'
  | 'booking_confirmed'
  | 'redemption_milestone';

export interface AppNotification {
  id: string;
  type: NotificationType;
  /** Which portal this notification surfaces in. */
  audience: Portal;
  /** `null` = every user of that portal. */
  recipientId: string | null;
  title: string;
  body: string;
  link: string;
  read: boolean;
  createdAt: string;
}

// ── Promo code ledger ─────────────────────────────────────────────────

export type PromoTier = 'A' | 'B' | 'C' | 'D' | 'E' | 'F' | 'G' | 'H' | 'I' | 'J';

/**
 * Records who owns a promo code so uniqueness and the per-restaurant monthly
 * tier rules can be enforced. One entry per issued code.
 */
export interface PromoCodeRecord {
  code: string;
  campaignId: string;
  restaurantId: string;
  /** `restaurantId:YYYY-MM` — the calendar month the exclusive tier was consumed in. */
  monthKey: string;
  tier: PromoTier;
  /** True for E–J: may be issued only once per restaurant per calendar month. */
  exclusive: boolean;
  issuedAt: string;
}
// ── Restaurant ────────────────────────────────────────────────────────

export type RestaurantStatus = 'active' | 'paused' | 'suspended';

export interface Restaurant {
  id: string;
  name: string;
  slug: string;
  city: string;
  region: string;
  cuisine: string[];
  address: string;
  /** Denormalised average of this restaurant's reviews (0–5). */
  rating: number;
  priceLevel: 1 | 2 | 3 | 4;
  /** Local asset path. Never a hotlink — see README §Assets. */
  imageUrl: string;
  status: RestaurantStatus;
  subscriptionId?: string;
  createdAt: string;
}

export type SubscriptionTier = 'basic' | 'partner' | 'premium';
export type SubscriptionStatus = 'active' | 'trialing' | 'past_due' | 'cancelled';

export interface Subscription {
  id: string;
  /** 1:1 with Restaurant. */
  restaurantId: string;
  tier: SubscriptionTier;
  status: SubscriptionStatus;
  billingCycle: 'monthly' | 'annual';
  amountInPaise: number;
  startedAt: string;
  renewsAt: string;
  cancelledAt?: string;
}

// ── Creator ───────────────────────────────────────────────────────────

export interface Creator {
  id: string;
  /** Unique, without the leading '@'. */
  handle: string;
  displayName: string;
  avatarUrl: string;
  city: string;
  niche: string[];
  followers: number;
  verified: boolean;
  createdAt: string;
}

export interface Review {
  id: string;
  creatorId: string;
  restaurantId: string;
  rating: 1 | 2 | 3 | 4 | 5;
  body?: string;
  createdAt: string;
}
/**
 * Metrics — the single source of numeric truth.
 *
 * Every figure rendered by the Admin, Restaurant and Creator portals is produced here. No
 * screen computes a count inline. That constraint is what makes the three portals agree: the
 * admin's "Total Verified Diners" and a creator's campaign report call the same selector over
 * the same records, so they cannot drift.
 *
 * The four distinctions the product depends on
 * ─────────────────────────────────────────────
 *  • QR Scans           — valid `ScanEvent`s. Interest only.
 *  • Redemptions        — `Redemption` rows. Staff-confirmed at bill settlement.
 *  • Verified Customers — DISTINCT `customerRef` across redemptions. Not a redemption count:
 *                         one diner visiting twice is one customer and two redemptions.
 *  • Discounts Provided — summed from redemptions only, never incremented at scan time.
 *
 * A scan therefore never counts as a verified customer. 142 scans → 38 redemptions → 31
 * distinct customers is a healthy funnel, not an inconsistency.
 */

import type { Campaign, Creator, Redemption, Restaurant, ScanEvent } from '@/types/models';

export interface CampaignMetrics {
  campaignId: string;
  /** Valid scans via camera. */
  qrScans: number;
  /** Valid scans via typed code. */
  manualScans: number;
  /** All valid scans, regardless of method. */
  totalScans: number;
  /** Scans rejected by the validation ladder, for diagnostics. */
  invalidScans: number;
  redemptionCount: number;
  /** DISTINCT customers. */
  verifiedCustomers: number;
  discountProvidedInPaise: number;
  revenueInfluencedInPaise: number;
  /** redemptions / totalScans, or 0 when there were no scans. */
  scanToRedemptionRate: number;
  /** Target from the campaign's own field — the cap encoded in the promo code. */
  targetCustomers: number;
  remainingQuota: number;
  quotaUsedPercent: number;
}

export interface CreatorMetrics {
  creatorId: string;
  campaignCount: number;
  activeCampaignCount: number;
  verifiedCustomers: number;
  redemptions: number;
  totalScans: number;
  discountProvidedInPaise: number;
  revenueInfluencedInPaise: number;
}

export interface RestaurantMetrics {
  restaurantId: string;
  campaignCount: number;
  activeCampaignCount: number;
  totalScans: number;
  redemptions: number;
  verifiedCustomers: number;
  discountProvidedInPaise: number;
  revenueInfluencedInPaise: number;
  reviewCount: number;
  averageRating: number;
}

export interface PlatformMetrics {
  restaurantCount: number;
  activeRestaurantCount: number;
  creatorCount: number;
  creatorVerifiedCustomers: number;
  campaignCount: number;
  activeCampaignCount: number;
  pendingApprovalCount: number;
  totalBookings: number;
  totalScans: number;
  totalRedemptions: number;
  totalVerifiedCustomers: number;
  discountProvidedInPaise: number;
  revenueInfluencedInPaise: number;
  /** Platform subscription revenue in paise. */
  saasRevenueInPaise: number;
}

export interface DataSet {
  restaurants: Restaurant[];
  creators: Creator[];
  campaigns: Campaign[];
  redemptions: Redemption[];
  scans: ScanEvent[];
  reviews: Array<{ restaurantId: string; rating: number }>;
  subscriptions: Array<{ restaurantId: string; status: string; amountInPaise: number }>;
  bookings: Array<{ status: string }>;
}

/**
 * Stable identity for a diner.
// ── Per-campaign ───────────────────────────────────────────────────────

/**
 * Metrics for one campaign.
 *
 * `campaign` is required (for the target) but scans/redemptions may be empty slices — an
 * approved campaign with no traffic must report zeros, never NaN.
 */
export function selectCampaignMetrics(
  campaign: Campaign,
  allScans: ScanEvent[],
  allRedemptions: Redemption[],
): CampaignMetrics {
  const campaignScans = allScans.filter((s) => s.campaignId === campaign.id);
  const validScans = campaignScans.filter((s) => s.result === 'valid');
  const campaignRedemptions = allRedemptions.filter((r) => r.campaignId === campaign.id);

  const qrScans = validScans.filter((s) => s.method === 'qr').length;
  const manualScans = validScans.length - qrScans;

  // A Set, never an array length — this is the anti-double-counting guarantee.
  const verifiedCustomers = new Set(
    campaignRedemptions.map((r, i) => customerIdentity(r, i)),
  ).size;

  const discountProvidedInPaise = campaignRedemptions.reduce(
    (sum, r) => sum + r.discountAmountInPaise,
    0,
  );
  const revenueInfluencedInPaise = campaignRedemptions.reduce(
    (sum, r) => sum + r.billAmountInPaise,
    0,
  );

  const redemptionCount = campaignRedemptions.length;
  const remainingQuota = Math.max(0, campaign.targetCustomers - redemptionCount);

  return {
    campaignId: campaign.id,
    qrScans,
    manualScans,
    totalScans: validScans.length,
    invalidScans: campaignScans.length - validScans.length,
    redemptionCount,
    verifiedCustomers,
    discountProvidedInPaise,
    revenueInfluencedInPaise,
    scanToRedemptionRate: validScans.length ? redemptionCount / validScans.length : 0,
    targetCustomers: campaign.targetCustomers,
    remainingQuota,
    quotaUsedPercent: percentOf(redemptionCount, campaign.targetCustomers),
  };
}

// ── Aggregations ───────────────────────────────────────────────────────

export function selectCreatorMetrics(
  creatorId: string,
  data: Pick<DataSet, 'campaigns' | 'scans' | 'redemptions'>,
): CreatorMetrics {
  const campaigns = data.campaigns.filter((c) => c.creatorId === creatorId);
  const ids = new Set(campaigns.map((c) => c.id));

  const scans = data.scans.filter((s) => ids.has(s.campaignId) && s.result === 'valid');
  const redemptions = data.redemptions.filter((r) => ids.has(r.campaignId));

  return {
    creatorId,
    campaignCount: campaigns.length,
    activeCampaignCount: campaigns.filter((c) => c.status === 'live' || c.status === 'approved')
      .length,
    verifiedCustomers: new Set(redemptions.map((r, i) => customerIdentity(r, i))).size,
    redemptions: redemptions.length,
    totalScans: scans.length,
    discountProvidedInPaise: redemptions.reduce((s, r) => s + r.discountAmountInPaise, 0),
    revenueInfluencedInPaise: redemptions.reduce((s, r) => s + r.billAmountInPaise, 0),
  };
}

export function selectRestaurantMetrics(
  restaurantId: string,
  data: Pick<DataSet, 'campaigns' | 'scans' | 'redemptions' | 'reviews'>,
): RestaurantMetrics {
  const campaigns = data.campaigns.filter((c) => c.restaurantId === restaurantId);
  const ids = new Set(campaigns.map((c) => c.id));

  const scans = data.scans.filter((s) => ids.has(s.campaignId) && s.result === 'valid');
  const redemptions = data.redemptions.filter((r) => ids.has(r.campaignId));

  const reviews = data.reviews.filter((r) => r.restaurantId === restaurantId);
  const averageRating = reviews.length
    ? reviews.reduce((s, r) => s + r.rating, 0) / reviews.length
    : 0;

  return {
    restaurantId,
    campaignCount: campaigns.length,
    activeCampaignCount: campaigns.filter((c) => c.status === 'live' || c.status === 'approved')
      .length,
    totalScans: scans.length,
    redemptions: redemptions.length,
    verifiedCustomers: new Set(redemptions.map((r, i) => customerIdentity(r, i))).size,
    discountProvidedInPaise: redemptions.reduce((s, r) => s + r.discountAmountInPaise, 0),
    revenueInfluencedInPaise: redemptions.reduce((s, r) => s + r.billAmountInPaise, 0),
    reviewCount: reviews.length,
    averageRating,
  };
}

/**
 * Stable identity for a diner.
 *
 * Redemptions carry an anonymous `customerRef`; where it is missing (an unidentified walk-in)
 * each redemption is treated as its own customer — the conservative choice, since it can never
 * push the verified count above the redemption count.
 */
function customerIdentity(redemption: Redemption, index: number): string {
  return redemption.customerRef ?? `anon:${redemption.id || index}`;
}

/** Percent of `current` against `total`, clamped to 0–100. */
function percentOf(current: number, total: number): number {
  if (total <= 0) return 0;
  return Math.min(100, Math.round((current / total) * 100));
}
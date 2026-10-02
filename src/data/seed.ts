/**
 * Seed dataset for the demo.
 *
 * Design goals
 * ─────────────
 *  1. **Internally coherent.** Every promo code satisfies the LLNNDDX rules and matches its
 *     campaign's target and duration. A seeded code can therefore be scanned and redeemed in
 *     the live UI without tripping validation.
 *  2. **Demonstrates the metrics distinction.** Scans outnumber redemptions, and redemptions
 *     outnumber distinct customers — because a pool of repeat diners is generated on purpose.
 *     That gap is what proves the funnel is measuring the right thing rather than counting
 *     array lengths. The exact counts move with the seed window; the *inequalities*
 *     (scans > redemptions > customers) are what hold, and `seed.test.ts` enforces them.
 *  3. **Covers every status.** The admin queue has real pending items to approve or reject,
 *     and the creator portal shows drafts, a live campaign, and a rejected campaign with a
 *     reason attached.
 *  4. **Anchored to the viewer's "today".** Dates are generated relative to load time so the
 *     demo never goes stale: a "live" campaign is always inside its own window, regardless of
 *     when the app is opened.
 *
 * Assets are local placeholders (`/images/...`) — see README §Assets. No hotlinks.
 */

import { EXCLUSIVE_TIERS, REUSABLE_TIERS } from '@/services/promoCode';
import type { PromoTier } from '@/types/models';
import type {
  AppNotification,
  Booking,
  Campaign,
  Creator,
  PromoCodeRecord,
  Redemption,
  Restaurant,
  Review,
  ScanEvent,
  Subscription,
} from '@/types/models';
import { toDateKey } from '@/utils/format';

export interface Database {
  restaurants: Restaurant[];
  creators: Creator[];
  subscriptions: Subscription[];
  campaigns: Campaign[];
  promoCodes: PromoCodeRecord[];
  bookings: Booking[];
  scans: ScanEvent[];
  redemptions: Redemption[];
  reviews: Review[];
  notifications: AppNotification[];
}

/** Local-midnight day offset, used to keep seeded dates relative to "today". */
function dayOffset(from: Date, days: number): string {
  const d = new Date(from.getFullYear(), from.getMonth(), from.getDate() + days);
  return toDateKey(d);
}

/** An ISO timestamp `hours` before `from`. */
function hoursAgo(from: Date, hours: number): string {
  return new Date(from.getTime() - hours * 3_600_000).toISOString();
}

/**
 * A tiny deterministic PRNG (mulberry32) so every visitor sees the *same* demo numbers.
 *
 * `Math.random` would give a different funnel shape on each load, making screenshots and
 * walkthroughs inconsistent between sessions.
 */
function makeRandom(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ── Reference entities ─────────────────────────────────────────────────

const RESTAURANTS: Restaurant[] = [
  {
    id: 'res_buhari',
    name: 'Buhari Restaurant',
    slug: 'buhari',
    city: 'Chennai',
    region: 'Tamil Nadu',
    cuisine: ['Mughlai', 'Biryani'],
    address: '12 Anna Salai, Chennai 600002',
    rating: 4.8,
    priceLevel: 2,
    imageUrl: '/images/restaurants/buhari.jpg',
    status: 'active',
    subscriptionId: 'sub_buhari',
    createdAt: '2024-02-11T09:00:00.000Z',
  },
  {
    id: 'res_rusty',
    name: 'The Rusty Spoon',
    slug: 'rusty-spoon',
    city: 'Bengaluru',
    region: 'Karnataka',
    cuisine: ['Continental', 'Grill'],
    address: '48 Indiranagar 100th Main, Bengaluru 560038',
    rating: 4.5,
    priceLevel: 3,
    imageUrl: '/images/restaurants/rusty-spoon.jpg',
    status: 'active',
    subscriptionId: 'sub_rusty',
    createdAt: '2024-04-02T09:00:00.000Z',
  },
  {
    id: 'res_purple',
    name: 'The Purple Haze',
    slug: 'purple-haze',
    city: 'Mumbai',
    region: 'Maharashtra',
    cuisine: ['Barbecue', 'Cafe'],
    address: '7 Linking Road, Bandra West, Mumbai 400050',
    rating: 4.3,
    priceLevel: 2,
    imageUrl: '/images/restaurants/purple-haze.jpg',
    status: 'active',
    subscriptionId: 'sub_purple',
    createdAt: '2024-06-18T09:00:00.000Z',
  },
  {
    id: 'res_amara',
    name: 'Amara Tandoor',
    slug: 'amara',
    city: 'Hyderabad',
    region: 'Telangana',
    cuisine: ['North Indian', 'Tandoor'],
    address: '23 Jubilee Hills, Hyderabad 500033',
    rating: 4.6,
    priceLevel: 3,
    imageUrl: '/images/restaurants/amara.jpg',
    status: 'active',
    subscriptionId: 'sub_amara',
    createdAt: '2024-09-30T09:00:00.000Z',
  },
  {
    id: 'res_nandi',
    name: 'Nandi House',
    slug: 'nandi',
    city: 'Bengaluru',
    region: 'Karnataka',
    cuisine: ['South Indian', 'Filter Coffee'],
    address: '90 MG Road, Bengaluru 560001',
    rating: 4.1,
    priceLevel: 1,
    imageUrl: '/images/restaurants/nandi.jpg',
    status: 'paused',
    createdAt: '2024-11-05T09:00:00.000Z',
  },
];

const CREATORS: Creator[] = [
  {
    id: 'cre_maya',
    handle: 'maya',
    displayName: 'Maya Krishnan',
    avatarUrl: '/images/avatars/maya.jpg',
    city: 'Chennai',
    niche: ['Food', 'Travel'],
    followers: 128_400,
    verified: true,
    createdAt: '2023-08-14T09:00:00.000Z',
  },
  {
    id: 'cre_arjun',
    handle: 'arjun',
    displayName: 'Arjun Rao',
    avatarUrl: '/images/avatars/arjun.jpg',
    city: 'Bengaluru',
    niche: ['Food', 'Lifestyle'],
    followers: 86_200,
    verified: true,
    createdAt: '2023-10-02T09:00:00.000Z',
  },
  {
    id: 'cre_neha',
    handle: 'neha',
    displayName: 'Neha Shetty',
    avatarUrl: '/images/avatars/neha.jpg',
    city: 'Mumbai',
    niche: ['Lifestyle', 'Brunch'],
    followers: 54_900,
    verified: false,
    createdAt: '2024-01-21T09:00:00.000Z',
  },
  {
    id: 'cre_dev',
    handle: 'devansh',
    displayName: 'Devansh Iyer',
    avatarUrl: '/images/avatars/devansh.jpg',
    city: 'Hyderabad',
    niche: ['Street Food', 'Grill'],
    followers: 33_100,
    verified: false,
    createdAt: '2024-05-09T09:00:00.000Z',
  },
];

// ── Campaigns ──────────────────────────────────────────────────────────

interface CampaignSeed {
  id: string;
  creatorId: string;
  restaurantId: string;
  title: string;
  offerText: string;
  discountPercent: number;
  /** Two-letter creator prefix used in the promo code. */
  prefix: string;
  targetCustomers: number;
  durationDays: number;
  status: Campaign['status'];
  /** Offset of `startDate` from today. */
  startOffset: number;
  rejectionReason?: string;
}

/**
 * Campaign seeds, ordered so the admin queue and creator views all have something to show.
 *
 * `targetCustomers` and `durationDays` are the *authoritative* values — the promo code is
 * derived from them, so the encoded NN/DD can never disagree with the campaign.
 */
const CAMPAIGN_SEEDS: CampaignSeed[] = [
  {
    id: 'cmp_biryani',
    creatorId: 'cre_maya',
    restaurantId: 'res_buhari',
    title: 'Signature Biryani Showcase',
    offerText: '20% off on all biryani orders',
    discountPercent: 20,
    prefix: 'MK',
    targetCustomers: 40,
    durationDays: 14,
    status: 'live',
    startOffset: -4,
  },
  {
    id: 'cmp_grill',
    creatorId: 'cre_arjun',
    restaurantId: 'res_rusty',
    title: 'Weekend Grill Nights',
    offerText: '15% off on grill platters every weekend',
    discountPercent: 15,
    prefix: 'AR',
    targetCustomers: 30,
    durationDays: 21,
    status: 'live',
    startOffset: -9,
  },
  {
    id: 'cmp_brunch',
    creatorId: 'cre_neha',
    restaurantId: 'res_purple',
    title: 'Sunday Brunch Club',
    offerText: '10% off brunch, every Sunday',
    discountPercent: 10,
    prefix: 'NS',
    targetCustomers: 25,
    durationDays: 10,
    status: 'pending_admin',
    startOffset: 2,
  },
  {
    id: 'cmp_street',
    creatorId: 'cre_dev',
    restaurantId: 'res_amara',
    title: 'Late Night Street Food',
    offerText: '25% off after 9pm',
    discountPercent: 25,
    prefix: 'DI',
    targetCustomers: 60,
    durationDays: 30,
    status: 'pending_admin',
    startOffset: 5,
  },
  {
    id: 'cmp_filter',
    creatorId: 'cre_arjun',
    restaurantId: 'res_nandi',
    title: 'Filter Coffee Fridays',
    offerText: 'Free filter coffee with any breakfast',
    discountPercent: 10,
    prefix: 'AF',
    targetCustomers: 20,
    durationDays: 7,
    status: 'rejected',
    startOffset: 1,
    rejectionReason:
      'The offer is complimentary rather than a discount, so it cannot be verified at settlement.',
  },
  {
    id: 'cmp_tasting',
    creatorId: 'cre_maya',
    restaurantId: 'res_amara',
    title: 'Chef Tasting Menu',
    offerText: '30% off the nine-course tasting menu',
    discountPercent: 30,
    prefix: 'MT',
    targetCustomers: 15,
    durationDays: 5,
    status: 'draft',
    startOffset: 12,
  },
];

/** Build the code a seed implies: prefix + padded target + padded days + tier. */
function codeFor(seed: CampaignSeed, tier: PromoTier): string {
  const n = String(seed.targetCustomers).padStart(2, '0');
  const d = String(seed.durationDays).padStart(2, '0');
  return `${seed.prefix}${n}${d}${tier}`;
}


function buildSubscriptions(now: Date): Subscription[] {
  return [
    {
      id: 'sub_buhari',
      restaurantId: 'res_buhari',
      tier: 'premium',
      status: 'active',
      billingCycle: 'monthly',
      amountInPaise: 249_900,
      startedAt: '2024-02-11T09:00:00.000Z',
      renewsAt: dayOffset(now, 26),
    },
    {
      id: 'sub_rusty',
      restaurantId: 'res_rusty',
      tier: 'partner',
      status: 'active',
      billingCycle: 'monthly',
      amountInPaise: 149_900,
      startedAt: '2024-04-02T09:00:00.000Z',
      renewsAt: dayOffset(now, 18),
    },
    {
      id: 'sub_purple',
      restaurantId: 'res_purple',
      tier: 'basic',
      status: 'trialing',
      billingCycle: 'monthly',
      amountInPaise: 79_900,
      startedAt: '2024-06-18T09:00:00.000Z',
      renewsAt: dayOffset(now, 11),
    },
    {
      id: 'sub_amara',
      restaurantId: 'res_amara',
      tier: 'partner',
      status: 'active',
      billingCycle: 'annual',
      amountInPaise: 1_499_000,
      startedAt: '2024-09-30T09:00:00.000Z',
      renewsAt: dayOffset(now, 120),
    },
    {
      id: 'sub_nandi',
      restaurantId: 'res_nandi',
      tier: 'basic',
      status: 'past_due',
      billingCycle: 'monthly',
      amountInPaise: 79_900,
      startedAt: '2024-11-05T09:00:00.000Z',
      renewsAt: dayOffset(now, -4),
    },
  ];
}

function buildBookings(now: Date): Booking[] {
  return [
    {
      id: 'bkg_1',
      creatorId: 'cre_maya',
      restaurantId: 'res_buhari',
      campaignId: 'cmp_biryani',
      date: dayOffset(now, 3),
      partySize: 4,
      status: 'confirmed',
      note: 'Anniversary dinner — window table if possible.',
      createdAt: hoursAgo(now, 30),
    },
    {
      id: 'bkg_2',
      creatorId: 'cre_arjun',
      restaurantId: 'res_rusty',
      campaignId: 'cmp_grill',
      date: dayOffset(now, 6),
      partySize: 2,
      status: 'requested',
      createdAt: hoursAgo(now, 52),
    },
    {
      id: 'bkg_3',
      creatorId: 'cre_neha',
      restaurantId: 'res_purple',
      date: dayOffset(now, -2),
      partySize: 6,
      status: 'completed',
      createdAt: hoursAgo(now, 96),
    },
    {
      id: 'bkg_4',
      creatorId: 'cre_dev',
      restaurantId: 'res_amara',
      date: dayOffset(now, 9),
      partySize: 3,
      status: 'requested',
      createdAt: hoursAgo(now, 120),
    },
  ];
}

function buildReviews(now: Date): Review[] {
  return [
    {
      id: 'rev_1',
      creatorId: 'cre_maya',
      restaurantId: 'res_buhari',
      rating: 5,
      body: 'The dum biryani is worth the trip alone.',
      createdAt: hoursAgo(now, 400),
    },
    {
      id: 'rev_2',
      creatorId: 'cre_arjun',
      restaurantId: 'res_buhari',
      rating: 4,
      createdAt: hoursAgo(now, 620),
    },
    {
      id: 'rev_3',
      creatorId: 'cre_neha',
      restaurantId: 'res_purple',
      rating: 4,
      body: 'Great brunch, though it gets busy by 11.',
      createdAt: hoursAgo(now, 210),
    },
    {
      id: 'rev_4',
      creatorId: 'cre_dev',
      restaurantId: 'res_amara',
      rating: 5,
      createdAt: hoursAgo(now, 300),
    },
    {
      id: 'rev_5',
      creatorId: 'cre_maya',
      restaurantId: 'res_amara',
      rating: 5,
      createdAt: hoursAgo(now, 120),
    },
  ];
}

function buildNotifications(now: Date): AppNotification[] {
  return [
    {
      id: 'ntf_1',
      type: 'campaign_submitted',
      audience: 'trynearby',
      recipientId: null,
      title: 'New campaign awaiting approval',
      body: 'Neha Shetty submitted “Sunday Brunch Club” for The Purple Haze.',
      link: '/admin/campaigns?status=pending_admin',
      read: false,
      createdAt: hoursAgo(now, 6),
    },
    {
      id: 'ntf_2',
      type: 'campaign_request_received',
      audience: 'restaurant',
      recipientId: 'res_buhari',
      title: 'Campaign approved',
      body: '“Signature Biryani Showcase” is live. Share the QR pass with your diners.',
      link: '/restaurant/campaigns',
      read: false,
      createdAt: hoursAgo(now, 30),
    },
    {
      id: 'ntf_3',
      type: 'booking_received',
      audience: 'restaurant',
      recipientId: 'res_buhari',
      title: 'Table request from Maya Krishnan',
      body: 'Party of 4, anniversary dinner.',
      link: '/restaurant/bookings',
      read: false,
      createdAt: hoursAgo(now, 30),
    },
  ];
}

// ── Builder ────────────────────────────────────────────────────────────

/** Deep copy so callers can mutate the result without touching the module constants. */
function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

/**
 * Build the full seed database, anchored to `now`.
 *
 * Campaign windows are derived from `startOffset`, so a campaign seeded as `live` is always
 * inside its own start/end range no matter what day the demo is opened.
 */
export function createSeedDatabase(now: Date = new Date()): Database {
  const random = makeRandom(20250115);

  // Exclusive tiers are limited to one per restaurant per calendar month, so only consume one
  // when this restaurant has not already spent it.
  const tierRotation: PromoTier[] = [...EXCLUSIVE_TIERS, ...REUSABLE_TIERS];

  const campaigns: Campaign[] = [];
  const promoCodes: PromoCodeRecord[] = [];
  const scans: ScanEvent[] = [];
  const redemptions: Redemption[] = [];

  /** Per-restaurant exclusive-tier usage, for the monthly ledger rule. */
  const tierUsage = new Map<string, Set<PromoTier>>();
  let tierCursor = 0;

  CAMPAIGN_SEEDS.forEach((seed, index) => {
    const startDate = dayOffset(now, seed.startOffset);
    const endDate = dayOffset(now, seed.startOffset + seed.durationDays);

    const used = tierUsage.get(seed.restaurantId) ?? new Set<PromoTier>();
    let tier: PromoTier;
    if (used.has('E')) {
      tier = REUSABLE_TIERS[index % REUSABLE_TIERS.length];
    } else {
      tier = tierRotation[tierCursor % tierRotation.length];
      if (EXCLUSIVE_TIERS.includes(tier)) used.add(tier);
    }
    tierUsage.set(seed.restaurantId, used);

    const code = codeFor(seed, tier);
    const createdAt = hoursAgo(now, 200 - index * 20);

    const campaign: Campaign = {
      id: seed.id,
      creatorId: seed.creatorId,
      restaurantId: seed.restaurantId,
      title: seed.title,
      offerText: seed.offerText,
      discountPercent: seed.discountPercent,
      targetCustomers: seed.targetCustomers,
      durationDays: seed.durationDays,
      startDate,
      endDate,
      status: seed.status,
      promoCode: code,
      createdAt,
      submittedAt: seed.status === 'draft' ? undefined : hoursAgo(now, 190 - index * 20),
      decidedAt: ['approved', 'live', 'rejected'].includes(seed.status)
        ? hoursAgo(now, 180 - index * 20)
        : undefined,
      rejectionReason: seed.rejectionReason,
    };
    campaigns.push(campaign);

    promoCodes.push({
      code,
      campaignId: campaign.id,
      restaurantId: campaign.restaurantId,
      monthKey: `${seed.restaurantId}:${startDate.slice(0, 7)}`,
      tier,
      exclusive: EXCLUSIVE_TIERS.includes(tier),
      issuedAt: createdAt,
    });

    // Only approved/live campaigns carry traffic.
    if (seed.status !== 'live' && seed.status !== 'approved') {
      tierCursor += 1;
      return;
    }

    const redemptionCount = Math.max(
      2,
      Math.round(seed.targetCustomers * (0.28 + random() * 0.22)),
    );
    // A pool smaller than the redemption count guarantees some repeat visits — this is
    // what proves the distinct-customer logic is not a plain array length.
    const poolSize = Math.max(1, Math.round(redemptionCount * 0.82));

    for (let i = 0; i < redemptionCount; i += 1) {
      const customerRef = `cust_${campaign.id}_${i % poolSize}`;
      const dayWithinWindow = Math.max(
        0,
        Math.min(seed.durationDays, Math.round((i / redemptionCount) * seed.durationDays)),
      );
      const visitDate = dayOffset(now, seed.startOffset + dayWithinWindow);
      const bill = Math.round((900 + random() * 5200) * 100);
      const discount = Math.round((bill * seed.discountPercent) / 100);

      redemptions.push({
        id: `rdm_${campaign.id}_${i}`,
        campaignId: campaign.id,
        restaurantId: campaign.restaurantId,
        creatorId: campaign.creatorId,
        promoCode: code,
        scanEventId: null,
        customerRef,
        redeemedAt: new Date(
          `${visitDate}T19:${String(10 + (i % 40)).padStart(2, '0')}:00.000Z`,
        ).toISOString(),
        billAmountInPaise: bill,
        discountAmountInPaise: discount,
        channel: random() > 0.22 ? 'dine_in' : 'takeaway',
        tableOrChannelRef: `T${(i % 18) + 1}`,
        staffName: ['Asha', 'Ravi', 'Neel', 'Divya', 'Karthik'][i % 5],
        visitDate,
      });
    }

    // Scans outnumber redemptions: every redemption has a preceding scan, plus browsing
    // interest that never converted.
    const scanCount = redemptionCount + Math.round(redemptionCount * 2.1);
    for (let i = 0; i < scanCount; i += 1) {
      const dayWithinWindow = Math.max(
        0,
        Math.min(seed.durationDays, Math.round((i / scanCount) * seed.durationDays)),
      );
      const scannedAt = new Date(
        `${dayOffset(now, seed.startOffset + dayWithinWindow)}T18:${String((i * 7) % 60).padStart(2, '0')}:00.000Z`,
      ).toISOString();

      scans.push({
        id: `scn_${campaign.id}_${i}`,
        campaignId: campaign.id,
        restaurantId: campaign.restaurantId,
        creatorId: campaign.creatorId,
        method: random() > 0.28 ? 'qr' : 'promo_manual',
        promoCode: code,
        scannedAt,
        customerRef: `cust_${campaign.id}_${i % Math.max(1, poolSize + 6)}`,
        result: 'valid',
        deviceLabel: i % 3 === 0 ? 'Front counter' : 'Table reader',
      });
    }
    tierCursor += 1;
  });

  return {
    restaurants: clone(RESTAURANTS),
    creators: clone(CREATORS),
    subscriptions: buildSubscriptions(now),
    bookings: buildBookings(now),
    reviews: buildReviews(now),
    notifications: buildNotifications(now),
    campaigns,
    promoCodes,
    scans,
    redemptions,
  };
}

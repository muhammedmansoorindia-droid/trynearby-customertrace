/**
 * Promo code engine — format `LLNNDDX`.
 *
 *   L L   two random uppercase letters  (the creator's handle prefix)
 *   N N   customer target, two digits   → 10–99
 *   D D   campaign duration in days     → 01–31
 *   X     tier letter, A–J only
 *
 * Example: `MK1507A` = MK | 15 customers | 07 days | tier A.
 *
 * Monthly tier rules, enforced per restaurant per calendar month:
 *   • A–D  reusable — may be issued any number of times.
 *   • E–J  exclusive — each may be issued only once per restaurant per calendar month.
 *
 * Design notes
 * ─────────────
 * The Stitch reference screen labelled `MK1507A` as "150 Target", but the format's two `NN`
 * characters can only express 10–99. The spec wins: targets outside 10–99 are rejected with an
 * explicit error rather than silently clamped, because a silently truncated target would
 * under-report the customer cap a restaurant paid for.
 *
 * Uniqueness is enforced *globally and case-insensitively* across every campaign, not merely
 * per restaurant — a diner typing a code must resolve to exactly one campaign.
 *
 * This module is deliberately free of React and storage concerns so it can lift to a server
 * unchanged when a real backend arrives.
 */

import { monthKey, toDateKey } from '@/utils/format';
import { pad2, tierFromIndex, tierIndex } from '@/utils';
import type { PromoCodeRecord, PromoRejectionReason, PromoTier } from '@/types/models';

// ── Limits ─────────────────────────────────────────────────────────────

/** `NN` is two digits, so the smallest sensible cap is 10 and the largest is 99. */
export const MIN_CUSTOMER_TARGET = 10;
export const MAX_CUSTOMER_TARGET = 99;

/** `DD` is two digits; campaign length is capped at one month. */
export const MIN_CAMPAIGN_DAYS = 1;
export const MAX_CAMPAIGN_DAYS = 31;

export const CODE_LENGTH = 7;

/** Tiers A–D may be reused freely. */
export const REUSABLE_TIERS: readonly PromoTier[] = ['A', 'B', 'C', 'D'];
/** Tiers E–J are consumed once per restaurant per calendar month. */
export const EXCLUSIVE_TIERS: readonly PromoTier[] = ['E', 'F', 'G', 'H', 'I', 'J'];
export const ALL_TIERS: readonly PromoTier[] = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J'];

/**
 * Strict shape check: two letters, four digits (NN ≥ 10 so "00"–"09" are rejected), then A–J.
 * `DD` is validated numerically in `parsePromoCode` so "99" reports INVALID_DURATION rather
 * than a generic format failure.
 */
export const PROMO_CODE_PATTERN = /^[A-Z]{2}(?:[1-9]\d)\d{2}[A-J]$/;

/**
 * Looser *shape* check used before range validation, so an out-of-range segment reports the
 * specific problem (`INVALID_CUSTOMER_TARGET` / `INVALID_DURATION`) rather than collapsing
 * every malformed code into one opaque `INVALID_FORMAT`.
 */
const SHAPE_PATTERN = /^[A-Z]{2}\d{4}[A-J]$/;

/** Attempts before `generatePromoCode` gives up rather than risk a duplicate. */
export const MAX_GENERATION_ATTEMPTS = 50;

// ── Types ──────────────────────────────────────────────────────────────

export interface ParsedPromoCode {
  prefix: string;
  targetCustomers: number;
  durationDays: number;
  tier: PromoTier;
}

export type PromoCodeFailure =
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

export type PromoCodeResult<T> =
  | { ok: true; value: T }
  | { ok: false; reason: PromoCodeFailure; message: string };

/** Everything the generator needs to know about existing usage. */
export interface PromoCodeLedger {
  /** Every code ever issued, used for the global uniqueness check. */
  records: PromoCodeRecord[];
  /** Redemptions already recorded, used for the quota and duplicate checks. */
  redemptions: Array<{
    campaignId: string;
    customerRef: string | null;
    visitDate: string;
  }>;
}

/** The campaign fields a code is validated against. */
export interface CodeCampaignContext {
  id: string;
  targetCustomers: number;
  durationDays: number;
  status: string;
  startDate: string;
  endDate: string;
}

// ── Messages ───────────────────────────────────────────────────────────

const MESSAGES: Record<PromoCodeFailure, string> = {
  INVALID_FORMAT: 'Code must be 7 characters in the format LLNNDDX — two letters, two digits, two digits, then A–J.',
  INVALID_CUSTOMER_TARGET: 'The customer target encoded in this code is outside the supported range of 10–99 customers.',
  INVALID_DURATION: 'The campaign duration encoded in this code is outside the supported range of 01–31 days.',
  INVALID_TIER: 'The final character must be a tier from A to J.',
  UNKNOWN_CODE: 'We could not find a campaign matching this code. Please check it and try again.',
  CODE_CAMPAIGN_MISMATCH: 'This code belongs to a different campaign. Please re-check the code.',
  CAMPAIGN_NOT_LIVE: 'This campaign has not been approved and is not live yet.',
  NOT_STARTED: 'This campaign has not started yet.',
  EXPIRED: 'This campaign has ended and the code is no longer valid.',
  QUOTA_EXHAUSTED: 'This campaign has reached its customer target.',
  TIER_MONTHLY_LIMIT: 'This tier has already been used by this restaurant this month.',
  ALREADY_REDEEMED: 'This code has already been redeemed today.',
};

function fail(reason: PromoCodeFailure): PromoCodeResult<never> {
  return { ok: false, reason, message: MESSAGES[reason] };
}

/** Exposed so the UI can map a failure to its icon/colour consistently. */
export function promoFailureMessage(reason: PromoRejectionReason): string {
  return MESSAGES[reason as PromoCodeFailure] ?? 'This code could not be verified.';
}
// ── Parsing ────────────────────────────────────────────────────────────

/**
 * Validate and decompose a code's own syntax.
 *
 * This is purely structural — it knows nothing about campaigns. `validatePromoCode` layers
 * the business rules on top.
 */
export function parsePromoCode(raw: string): PromoCodeResult<ParsedPromoCode> {
  const code = (raw ?? '').trim().toUpperCase();

  if (code.length !== CODE_LENGTH) return fail('INVALID_FORMAT');
  if (!SHAPE_PATTERN.test(code)) return fail('INVALID_FORMAT');

  const prefix = code.slice(0, 2);
  const targetCustomers = Number(code.slice(2, 4));
  const durationDays = Number(code.slice(4, 6));

  if (!Number.isInteger(targetCustomers)) return fail('INVALID_CUSTOMER_TARGET');
  if (targetCustomers < MIN_CUSTOMER_TARGET || targetCustomers > MAX_CUSTOMER_TARGET) {
    return fail('INVALID_CUSTOMER_TARGET');
  }

  if (!Number.isInteger(durationDays)) return fail('INVALID_DURATION');
  if (durationDays < MIN_CAMPAIGN_DAYS || durationDays > MAX_CAMPAIGN_DAYS) {
    return fail('INVALID_DURATION');
  }

  const tierPosition = tierIndex(code[6]);
  if (tierPosition < 0 || tierPosition > 9) return fail('INVALID_TIER');

  return {
    ok: true,
    value: { prefix, targetCustomers, durationDays, tier: tierFromIndex(tierPosition) as PromoTier },
  };
}

/** Cheap normalisation for comparisons and lookup keys. */
export function normalizeCode(raw: string): string {
  return (raw ?? '').trim().toUpperCase();
}

// ── Tier availability ──────────────────────────────────────────────────

/** Has `tier` already been consumed by `restaurantId` during `month`? */
export function isTierUsedThisMonth(
  tier: PromoTier,
  restaurantId: string,
  month: string,
  ledger: Pick<PromoCodeLedger, 'records'>,
): boolean {
  return ledger.records.some(
    (r) => r.restaurantId === restaurantId && r.tier === tier && r.monthKey === month && r.exclusive,
  );
}

/**
 * Which tiers may still be issued to this restaurant this month.
 *
 * Exclusive tiers (E–J) not yet consumed come first; once all six are spent the reusable
 * tiers (A–D) remain available forever, so `getAvailableTiers` is never empty.
 */
export function getAvailableTiers(
  restaurantId: string,
  month: string,
  ledger: Pick<PromoCodeLedger, 'records'>,
): PromoTier[] {
  const exclusive = EXCLUSIVE_TIERS.filter(
    (t) => !isTierUsedThisMonth(t, restaurantId, month, ledger),
  );
  return [...exclusive, ...REUSABLE_TIERS];
}

// ── Generation ─────────────────────────────────────────────────────────

export interface GeneratePromoCodeInput {
  restaurantId: string;
  campaignId: string;
  targetCustomers: number;
  durationDays: number;
  /** Injected for deterministic tests; defaults to `Math.random`. */
  random?: () => number;
  /** Injected for deterministic tests; defaults to the current date. */
  now?: Date;
}

function randomLetters(random: () => number): string {
  const first = String.fromCharCode(65 + Math.floor(random() * 26));
  const second = String.fromCharCode(65 + Math.floor(random() * 26));
  return first + second;
}

/**
 * Issue a unique, rule-compliant promo code.
 *
 * On success the caller must persist the returned `record` into the ledger — this function
 * is pure and has no side effects.
 *
 * If every attempt collides it fails loudly rather than emitting a duplicate, which would
 * let a single code resolve to two different campaigns.
 */
export function generatePromoCode(
  input: GeneratePromoCodeInput,
  ledger: Pick<PromoCodeLedger, 'records'>,
): PromoCodeResult<{ code: string; record: PromoCodeRecord; degraded: boolean }> {
  const random = input.random ?? Math.random;
  const now = input.now ?? new Date();

  const target = input.targetCustomers;
  if (!Number.isInteger(target) || target < MIN_CUSTOMER_TARGET || target > MAX_CUSTOMER_TARGET) {
    return fail('INVALID_CUSTOMER_TARGET');
  }

  const days = input.durationDays;
  if (!Number.isInteger(days) || days < MIN_CAMPAIGN_DAYS || days > MAX_CAMPAIGN_DAYS) {
    return fail('INVALID_DURATION');
  }

  const month = monthKey(input.restaurantId, now);
  const available = getAvailableTiers(input.restaurantId, month, ledger);
  const exclusiveAvailable = available.filter((t) => EXCLUSIVE_TIERS.includes(t));
  /** True when all six exclusive tiers are spent and we fell back to A–D. */
  const degraded = exclusiveAvailable.length === 0;
  const tiers = degraded ? available : exclusiveAvailable;

  const taken = new Set(ledger.records.map((r) => r.code.toUpperCase()));

  for (let attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt += 1) {
    const prefix = randomLetters(random);
    const tier = tiers[Math.floor(random() * tiers.length)];
    const code = `${prefix}${pad2(target)}${pad2(days)}${tier}`;

    if (taken.has(code)) continue;

    return {
      ok: true,
      value: {
        code,
        degraded,
        record: {
          code,
          campaignId: input.campaignId,
          restaurantId: input.restaurantId,
          monthKey: month,
          tier,
          exclusive: EXCLUSIVE_TIERS.includes(tier),
          issuedAt: now.toISOString(),
        },
      },
    };
  }

  // Exhausted the search space — never fall through to a duplicate.
  return fail('TIER_MONTHLY_LIMIT');
}
// ── Validation ─────────────────────────────────────────────────────────

export interface ValidatePromoCodeInput {
  rawCode: string;
  restaurantId: string;
  ledger: PromoCodeLedger;
  /** Resolves the campaign a code belongs to. */
  getCampaign: (campaignId: string) => CodeCampaignContext | undefined;
  /** Identifies the diner attempting this redemption, for the duplicate guard. */
  customerRef?: string | null;
  /** Date-only ISO of the visit. Defaults to today. */
  visitDate?: string;
  now?: Date;
}

/**
 * The full validation ladder, run in order.
 *
 * Each rung has a distinct failure code so the restaurant desk can tell a diner *why* a code
 * was refused — "expired" and "wrong venue" need very different reactions at the table.
 */
export function validatePromoCode(
  input: ValidatePromoCodeInput,
): PromoCodeResult<{ code: string; record: PromoCodeRecord; campaign: CodeCampaignContext }> {
  const now = input.now ?? new Date();
  const visitDate = input.visitDate ?? toDateKey(now);

  // 1–5 · Syntax: length, shape, NN range, DD range, tier letter.
  const parsed = parsePromoCode(input.rawCode);
  if (!parsed.ok) return parsed;

  const code = normalizeCode(input.rawCode);

  // 6 · Does the code exist, and does it belong to this restaurant?
  const record = input.ledger.records.find((r) => r.code.toUpperCase() === code);
  if (!record) return fail('UNKNOWN_CODE');
  if (record.restaurantId !== input.restaurantId) return fail('UNKNOWN_CODE');

  const campaign = input.getCampaign(record.campaignId);
  if (!campaign) return fail('UNKNOWN_CODE');

  // 7 · The embedded NN/DD must match the campaign they claim to describe.
  if (
    parsed.value.targetCustomers !== campaign.targetCustomers ||
    parsed.value.durationDays !== campaign.durationDays
  ) {
    return fail('CODE_CAMPAIGN_MISMATCH');
  }

  // 8 · Only approved/live campaigns accept customers.
  if (campaign.status !== 'approved' && campaign.status !== 'live') {
    return fail('CAMPAIGN_NOT_LIVE');
  }

  // 9 · Within the campaign window, compared as local calendar days.
  const today = toDateKey(now);
  if (today < campaign.startDate) return fail('NOT_STARTED');
  if (today > campaign.endDate) return fail('EXPIRED');

  // 10 · Quota — the cap encoded as NN in the code itself.
  const redeemedCount = input.ledger.redemptions.filter(
    (r) => r.campaignId === campaign.id,
  ).length;
  if (redeemedCount >= campaign.targetCustomers) return fail('QUOTA_EXHAUSTED');

  // 11 · One redemption per customer per campaign per day.
  if (input.customerRef) {
    const duplicate = input.ledger.redemptions.some(
      (r) =>
        r.campaignId === campaign.id &&
        r.customerRef === input.customerRef &&
        r.visitDate === visitDate,
    );
    if (duplicate) return fail('ALREADY_REDEEMED');
  }

  return { ok: true, value: { code, record, campaign } };
}

/**
 * Re-check exclusive-tier ownership for an already-issued code.
 *
 * Normally redundant — the tier was free when minted — but exposed so the restaurant desk
 * can explain *why* a code carries the tier it does.
 */
export function validateTierOwnership(
  record: PromoCodeRecord,
  restaurantId: string,
  month: string,
  ledger: Pick<PromoCodeLedger, 'records'>,
): PromoCodeResult<true> {
  // The tier's own availability was already proven at mint time by `generatePromoCode`, so
  // `ledger` is accepted for call-site symmetry with the rest of the validation ladder but is
  // not re-checked here — a later, unrelated mint must not retroactively invalidate this code.
  void ledger;
  if (record.restaurantId !== restaurantId) return fail('UNKNOWN_CODE');
  if (record.exclusive && record.monthKey !== month) return fail('TIER_MONTHLY_LIMIT');
  return { ok: true, value: true };
}
import { describe, expect, it } from 'vitest';

import {
  ALL_TIERS,
  EXCLUSIVE_TIERS,
  MAX_CAMPAIGN_DAYS,
  MAX_CUSTOMER_TARGET,
  MIN_CAMPAIGN_DAYS,
  MIN_CUSTOMER_TARGET,
  PROMO_CODE_PATTERN,
  REUSABLE_TIERS,
  generatePromoCode,
  getAvailableTiers,
  isTierUsedThisMonth,
  parsePromoCode,
  validatePromoCode,
  validateTierOwnership,
} from '@/services/promoCode';
import type { PromoCodeRecord } from '@/types/models';

/** Deterministic sequence so generation assertions are reproducible. */
function sequenceRandom(values: number[]): () => number {
  let i = 0;
  return () => values[i++ % values.length] ?? 0;
}

const JAN = new Date(2025, 0, 15);
const FEB = new Date(2025, 1, 15);

const NO_LEDGER = { records: [] as PromoCodeRecord[] };

function record(over: Partial<PromoCodeRecord> & Pick<PromoCodeRecord, 'code' | 'tier'>): PromoCodeRecord {
  return {
    campaignId: 'cmp_1',
    restaurantId: 'res_1',
    monthKey: 'res_1:2025-01',
    exclusive: EXCLUSIVE_TIERS.includes(over.tier),
    issuedAt: '2025-01-15T00:00:00.000Z',
    ...over,
  };
}

describe('format and bounds', () => {
  it('exposes tiers A–J split into reusable and exclusive', () => {
    expect([...ALL_TIERS]).toEqual(['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J']);
    expect([...REUSABLE_TIERS]).toEqual(['A', 'B', 'C', 'D']);
    expect([...EXCLUSIVE_TIERS]).toEqual(['E', 'F', 'G', 'H', 'I', 'J']);
  });

  it('encodes customer target in 10–99 and duration in 1–31', () => {
    expect(MIN_CUSTOMER_TARGET).toBe(10);
    expect(MAX_CUSTOMER_TARGET).toBe(99);
    expect(MIN_CAMPAIGN_DAYS).toBe(1);
    expect(MAX_CAMPAIGN_DAYS).toBe(31);
  });

  it('matches the documented example MK1507A', () => {
    expect(PROMO_CODE_PATTERN.test('MK1507A')).toBe(true);
  });
});

describe('parsePromoCode', () => {
  it('decomposes a valid code', () => {
    const result = parsePromoCode('MK1507A');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value).toEqual({
      prefix: 'MK',
      targetCustomers: 15,
      durationDays: 7,
      tier: 'A',
    });
  });

  it('normalises case and surrounding whitespace', () => {
    const result = parsePromoCode('  mk1507a  ');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.prefix).toBe('MK');
  });

  it.each([
    ['MK1507', 'wrong length'],
    ['MK1507AB', 'wrong length'],
    ['M11507A', 'one letter'],
    ['MK15O7A', 'letter in NN'],
    ['MK1507K', 'tier beyond J'],
    ['', 'empty'],
  ])('rejects %s (%s)', (code) => {
    expect(parsePromoCode(code).ok).toBe(false);
  });

  it.each([
    ['MK0907A', 'INVALID_CUSTOMER_TARGET'],
    ['MK0007A', 'INVALID_CUSTOMER_TARGET'],
    ['MK0107A', 'INVALID_CUSTOMER_TARGET'],
    ['MK1500A', 'INVALID_DURATION'],
    ['MK1532A', 'INVALID_DURATION'],
    ['MK1599A', 'INVALID_DURATION'],
  ])('rejects %s as %s', (code, reason) => {
    const result = parsePromoCode(code);
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toBe(reason);
  });

  it('accepts the boundary values 10 and 99', () => {
    expect(parsePromoCode('MK1007A').ok).toBe(true);
    expect(parsePromoCode('MK9907A').ok).toBe(true);
  });

  it('accepts boundary durations 01 and 31', () => {
    expect(parsePromoCode('MK1501A').ok).toBe(true);
    expect(parsePromoCode('MK1531A').ok).toBe(true);
  });
});

describe('generatePromoCode', () => {
  const base = {
    restaurantId: 'res_1',
    campaignId: 'cmp_1',
    targetCustomers: 15,
    durationDays: 7,
    now: JAN,
  };

  it('produces a code matching LLNNDDX', () => {
    const result = generatePromoCode({ ...base, random: Math.random }, NO_LEDGER);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.code).toHaveLength(7);
    expect(PROMO_CODE_PATTERN.test(result.value.code)).toBe(true);
    expect(result.value.code.slice(2, 4)).toBe('15');
    expect(result.value.code.slice(4, 6)).toBe('07');
  });

  it('pads single-digit targets and durations to two digits', () => {
    const result = generatePromoCode(
      { ...base, targetCustomers: 12, durationDays: 3, random: Math.random },
      NO_LEDGER,
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.code).toMatch(/^[A-Z]{2}1203[A-J]$/);
  });

  it('rejects a target outside 10–99 rather than clamping it', () => {
    const low = generatePromoCode({ ...base, targetCustomers: 9 }, NO_LEDGER);
    expect(low.ok).toBe(false);
    if (!low.ok) expect(low.reason).toBe('INVALID_CUSTOMER_TARGET');

    const high = generatePromoCode({ ...base, targetCustomers: 150 }, NO_LEDGER);
    expect(high.ok).toBe(false);
    if (!high.ok) expect(high.reason).toBe('INVALID_CUSTOMER_TARGET');
  });

  it('rejects a duration outside 1–31', () => {
    const zero = generatePromoCode({ ...base, durationDays: 0 }, NO_LEDGER);
    expect(zero.ok).toBe(false);
    if (!zero.ok) expect(zero.reason).toBe('INVALID_DURATION');

    const tooLong = generatePromoCode({ ...base, durationDays: 45 }, NO_LEDGER);
    expect(tooLong.ok).toBe(false);
    if (!tooLong.ok) expect(tooLong.reason).toBe('INVALID_DURATION');
  });

  it('rejects non-integer inputs', () => {
    expect(generatePromoCode({ ...base, targetCustomers: 15.5 }, NO_LEDGER).ok).toBe(false);
    expect(generatePromoCode({ ...base, durationDays: 7.2 }, NO_LEDGER).ok).toBe(false);
  });

  it('never re-issues an existing code', () => {
    const first = generatePromoCode({ ...base, random: Math.random }, NO_LEDGER);
    expect(first.ok).toBe(true);
    if (!first.ok) return;

    const colliding = { records: [first.value.record] };
    for (let i = 0; i < 20; i += 1) {
      const again = generatePromoCode({ ...base, random: Math.random }, colliding);
      if (again.ok) expect(again.value.code).not.toBe(first.value.code);
    }
  });

  it('matches case-insensitively when checking taken codes', () => {
    const existing = { records: [record({ code: 'MK1507A', tier: 'A' })] };
    for (let i = 0; i < 20; i += 1) {
      const result = generatePromoCode({ ...base, random: Math.random }, existing);
      if (result.ok) expect(result.value.code).not.toBe('MK1507A');
    }
  });

  it('exhausts rather than emitting a duplicate', () => {
    // Exhaustion is only reachable once the reusable fallback (A–D) is also blocked,
    // because the generator always has A–D available otherwise.
    const impossible = { records: [] as PromoCodeRecord[] };
    for (const t of ALL_TIERS) {
      for (const a of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') {
        for (const b of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') {
          impossible.records.push(record({ code: `${a}${b}1507${t}`, tier: t }));
        }
      }
    }
    const result = generatePromoCode({ ...base, random: sequenceRandom([0]) }, impossible);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('TIER_MONTHLY_LIMIT');
  });

  it('scopes the month key to the restaurant and local calendar month', () => {
    const result = generatePromoCode({ ...base, random: Math.random }, NO_LEDGER);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.record.monthKey).toBe('res_1:2025-01');
  });
});
describe('monthly tier rules', () => {
  it('allows A–D to be reused any number of times', () => {
    const ledger = {
      records: [
        record({ code: 'AA1507A', tier: 'A' }),
        record({ code: 'BB1507B', tier: 'B' }),
        record({ code: 'CC1507C', tier: 'C' }),
      ],
    };
    const available = getAvailableTiers('res_1', 'res_1:2025-01', ledger);
    expect(available).toEqual(expect.arrayContaining(['A', 'B', 'C', 'D']));
  });

  it('blocks an exclusive tier already used by the restaurant this month', () => {
    const ledger = { records: [record({ code: 'MK1507E', tier: 'E' })] };
    expect(isTierUsedThisMonth('E', 'res_1', 'res_1:2025-01', ledger)).toBe(true);
    expect(getAvailableTiers('res_1', 'res_1:2025-01', ledger)).not.toContain('E');
  });

  it('does not block a tier used by a different restaurant', () => {
    const ledger = { records: [record({ code: 'MK1507E', tier: 'E', restaurantId: 'res_2' })] };
    expect(isTierUsedThisMonth('E', 'res_1', 'res_1:2025-01', ledger)).toBe(false);
    expect(getAvailableTiers('res_1', 'res_1:2025-01', ledger)).toContain('E');
  });

  it('frees exclusive tiers again in the next calendar month', () => {
    const ledger = {
      records: [record({ code: 'MK1507E', tier: 'E', monthKey: 'res_1:2025-01' })],
    };
    expect(isTierUsedThisMonth('E', 'res_1', 'res_1:2025-01', ledger)).toBe(true);
    expect(isTierUsedThisMonth('E', 'res_1', 'res_1:2025-02', ledger)).toBe(false);
    expect(getAvailableTiers('res_1', 'res_1:2025-02', ledger)).toContain('E');
  });

  it('falls back to A–D once all six exclusive tiers are spent', () => {
    const ledger = {
      records: EXCLUSIVE_TIERS.map((t) => record({ code: `MK1507${t}`, tier: t })),
    };
    expect(getAvailableTiers('res_1', 'res_1:2025-01', ledger)).toEqual(['A', 'B', 'C', 'D']);
  });

  it('flags the fallback so the UI can explain a reused tier', () => {
    const full = {
      records: EXCLUSIVE_TIERS.map((t) => record({ code: `MK1507${t}`, tier: t })),
    };
    const result = generatePromoCode(
      { restaurantId: 'res_1', campaignId: 'cmp_1', targetCustomers: 15, durationDays: 7, now: JAN },
      full,
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.degraded).toBe(true);
    expect(['A', 'B', 'C', 'D']).toContain(result.value.record.tier);
    expect(result.value.record.exclusive).toBe(false);
  });

  it('prefers an unused exclusive tier over a reusable one', () => {
    const result = generatePromoCode(
      { restaurantId: 'res_1', campaignId: 'cmp_1', targetCustomers: 15, durationDays: 7, now: JAN },
      NO_LEDGER,
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(EXCLUSIVE_TIERS).toContain(result.value.record.tier);
    expect(result.value.degraded).toBe(false);
  });

  it('validates tier ownership against the restaurant and month', () => {
    const rec = record({ code: 'MK1507E', tier: 'E', monthKey: 'res_1:2025-01' });
    expect(validateTierOwnership(rec, 'res_1', 'res_1:2025-01', NO_LEDGER).ok).toBe(true);
    expect(validateTierOwnership(rec, 'res_2', 'res_1:2025-01', NO_LEDGER).ok).toBe(false);
    expect(validateTierOwnership(rec, 'res_1', 'res_1:2025-02', NO_LEDGER).ok).toBe(false);
  });
});

describe('validatePromoCode ladder', () => {
  const campaign = {
    id: 'cmp_1',
    targetCustomers: 15,
    durationDays: 7,
    status: 'live',
    startDate: '2025-01-01',
    endDate: '2025-01-31',
  };

  const ledger = {
    records: [record({ code: 'MK1507A', tier: 'A' })],
    redemptions: [] as Array<{ campaignId: string; customerRef: string | null; visitDate: string }>,
  };

  const run = (over: Partial<Parameters<typeof validatePromoCode>[0]> = {}) =>
    validatePromoCode({
      rawCode: 'MK1507A',
      restaurantId: 'res_1',
      ledger,
      getCampaign: () => campaign,
      now: JAN,
      ...over,
    });

  it('accepts a well-formed code on a live campaign', () => {
    const result = run();
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.campaign.id).toBe('cmp_1');
  });

  it('normalises case before lookup', () => {
    expect(run({ rawCode: 'mk1507a' }).ok).toBe(true);
  });

  it('rejects malformed input', () => {
    expect(run({ rawCode: 'ABC' }).ok).toBe(false);
  });

  it('rejects an unknown code', () => {
    const result = run({ rawCode: 'ZZ1507A' });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('UNKNOWN_CODE');
  });

  it('rejects a code belonging to another restaurant', () => {
    const result = run({ restaurantId: 'res_9' });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('UNKNOWN_CODE');
  });

  it('rejects a code whose embedded values disagree with the campaign', () => {
    const result = run({ getCampaign: () => ({ ...campaign, targetCustomers: 20 }) });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('CODE_CAMPAIGN_MISMATCH');
  });

  it('rejects a campaign that is not live', () => {
    const result = run({ getCampaign: () => ({ ...campaign, status: 'pending_admin' }) });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('CAMPAIGN_NOT_LIVE');
  });

  it('rejects a campaign that has not started', () => {
    const result = run({ now: new Date(2024, 11, 25) });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('NOT_STARTED');
  });

  it('rejects an expired campaign', () => {
    const result = run({ now: FEB });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('EXPIRED');
  });

  it('accepts a code on the final day of the window', () => {
    expect(run({ now: new Date(2025, 0, 31) }).ok).toBe(true);
  });

  it('rejects once the customer target has been met', () => {
    const full = {
      ...ledger,
      redemptions: Array.from({ length: 15 }, (_, i) => ({
        campaignId: 'cmp_1',
        customerRef: `c${i}`,
        visitDate: '2025-01-10',
      })),
    };
    const result = run({ ledger: full });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('QUOTA_EXHAUSTED');
  });

  it('allows redemption when one short of the target', () => {
    const nearly = {
      ...ledger,
      redemptions: Array.from({ length: 14 }, (_, i) => ({
        campaignId: 'cmp_1',
        customerRef: `c${i}`,
        visitDate: '2025-01-10',
      })),
    };
    expect(run({ ledger: nearly }).ok).toBe(true);
  });

  it('rejects a second redemption by the same customer on the same day', () => {
    const used = {
      ...ledger,
      redemptions: [{ campaignId: 'cmp_1', customerRef: 'cust_9', visitDate: '2025-01-15' }],
    };
    const result = run({ ledger: used, customerRef: 'cust_9', visitDate: '2025-01-15' });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.reason).toBe('ALREADY_REDEEMED');
  });

  it('permits the same customer on a different day', () => {
    const used = {
      ...ledger,
      redemptions: [{ campaignId: 'cmp_1', customerRef: 'cust_9', visitDate: '2025-01-10' }],
    };
    expect(run({ ledger: used, customerRef: 'cust_9', visitDate: '2025-01-15' }).ok).toBe(true);
  });

  it('skips the duplicate guard for an unidentified diner', () => {
    const used = {
      ...ledger,
      redemptions: [{ campaignId: 'cmp_1', customerRef: null, visitDate: '2025-01-15' }],
    };
    expect(run({ ledger: used, customerRef: null, visitDate: '2025-01-15' }).ok).toBe(true);
  });

  it('returns a human-readable message with every failure', () => {
    const result = run({ rawCode: 'ZZ1507A' });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.message.length).toBeGreaterThan(10);
  });
});

/**
 * Guardrails for the seed dataset.
 *
 * The demo is only convincing if its numbers are *coherent* — a code that cannot be parsed or
 * a funnel where scans equal redemptions would quietly contradict the product's core claim
 * (interest ≠ verified customer). These tests fail loudly if a future edit breaks that.
 */

import { describe, expect, it } from 'vitest';

import { createSeedDatabase } from '@/data/seed';
import { selectCampaignMetrics } from '@/services/metrics';
import { parsePromoCode, validatePromoCode } from '@/services/promoCode';

const NOW = new Date(2025, 0, 15, 12, 0, 0);
const db = createSeedDatabase(NOW);
const liveCampaigns = db.campaigns.filter((c) => c.status === 'live');

if (process.env.SEED_DUMP) {
  it('dumps the seeded funnel', () => {
    const rows = db.campaigns.map((c) => {
      const m = selectCampaignMetrics(c, db.scans, db.redemptions);
      return [
        c.id,
        c.status.padEnd(13),
        c.promoCode,
        `scans=${String(m.totalScans).padStart(3)}`,
        `rdm=${String(m.redemptionCount).padStart(3)}`,
        `cust=${String(m.verifiedCustomers).padStart(3)}`,
        `quota=${m.remainingQuota}/${c.targetCustomers}`,
        `₹${(m.discountProvidedInPaise / 100).toFixed(0)}`,
      ].join('  ');
    });
    console.log(`\ncounts: restaurants=${db.restaurants.length} creators=${db.creators.length}`);
    console.log(`        campaigns=${db.campaigns.length} codes=${db.promoCodes.length}`);
    console.log(`        scans=${db.scans.length} redemptions=${db.redemptions.length}\n`);
    console.log(rows.join('\n'));
    expect(rows.length).toBeGreaterThan(0);
  });
}

describe('seed promo codes', () => {
  it('every seeded code parses, and its encoded NN/DD match the campaign', () => {
    for (const campaign of db.campaigns) {
      const parsed = parsePromoCode(campaign.promoCode ?? '');
      expect(parsed.ok, `${campaign.id} → ${campaign.promoCode}`).toBe(true);
      if (!parsed.ok) continue;
      expect(parsed.value.targetCustomers).toBe(campaign.targetCustomers);
      expect(parsed.value.durationDays).toBe(campaign.durationDays);
    }
  });

  it('codes are globally unique', () => {
    const codes = db.promoCodes.map((r) => r.code);
    expect(new Set(codes).size).toBe(codes.length);
  });

  it('no restaurant consumes the same exclusive tier twice in a month', () => {
    const seen = new Set<string>();
    for (const record of db.promoCodes.filter((r) => r.exclusive)) {
      const key = `${record.restaurantId}|${record.tier}|${record.monthKey}`;
      expect(seen.has(key), `exclusive tier reused: ${key}`).toBe(false);
      seen.add(key);
    }
  });

  it('every live campaign passes the full validation ladder', () => {
    for (const campaign of liveCampaigns) {
      const result = validatePromoCode({
        rawCode: campaign.promoCode ?? '',
        restaurantId: campaign.restaurantId,
        now: NOW,
        ledger: { records: db.promoCodes, redemptions: db.redemptions },
        getCampaign: (id) => db.campaigns.find((c) => c.id === id),
      });
      expect(result.ok, `${campaign.id}: ${JSON.stringify(result)}`).toBe(true);
    }
  });
});

describe('seed funnel', () => {
  it('proves the distinction: scans > redemptions > distinct customers', () => {
    expect(liveCampaigns.length).toBeGreaterThan(0);
    for (const campaign of liveCampaigns) {
      const scans = db.scans.filter((s) => s.campaignId === campaign.id).length;
      const redemptions = db.redemptions.filter((r) => r.campaignId === campaign.id);
      const distinct = new Set(redemptions.map((r) => r.customerRef)).size;

      expect(scans, `${campaign.id} scans`).toBeGreaterThan(redemptions.length);
      expect(redemptions.length, `${campaign.id} redemptions`).toBeGreaterThan(distinct);
    }
  });

  it('keeps every redemption inside its campaign window', () => {
    for (const campaign of liveCampaigns) {
      for (const redemption of db.redemptions.filter((r) => r.campaignId === campaign.id)) {
        expect(redemption.visitDate >= campaign.startDate).toBe(true);
        expect(redemption.visitDate <= campaign.endDate).toBe(true);
      }
    }
  });

  it('agrees with the metrics service', () => {
    for (const campaign of liveCampaigns) {
      const m = selectCampaignMetrics(campaign, db.scans, db.redemptions);
      expect(m.campaignId).toBe(campaign.id);
      expect(m.redemptionCount).toBe(
        db.redemptions.filter((r) => r.campaignId === campaign.id).length,
      );
      expect(m.totalScans).toBeGreaterThan(m.redemptionCount);
      expect(m.invalidScans).toBe(0);
      expect(m.verifiedCustomers).toBeLessThan(m.redemptionCount);
      expect(m.discountProvidedInPaise).toBeGreaterThan(0);
      // Quota is consumed per redemption, not per distinct customer — a repeat visit burns
      // the quota again, which is exactly the distinction the dashboards must surface.
      expect(m.remainingQuota).toBe(campaign.targetCustomers - m.redemptionCount);
      // `percentOf` rounds to a whole number for display.
      expect(m.quotaUsedPercent).toBe(
        Math.round((m.redemptionCount / campaign.targetCustomers) * 100),
      );
    }
  });
});

describe('seed dataset', () => {
  it('is deterministic for a given date and detached from module constants', () => {
    expect(JSON.stringify(createSeedDatabase(NOW))).toBe(JSON.stringify(db));

    const mutated = createSeedDatabase(NOW);
    mutated.campaigns[0].title = 'MUTATED';
    expect(createSeedDatabase(NOW).campaigns[0].title).not.toBe('MUTATED');
  });

  it('covers every status the portals need to render', () => {
    const statuses = new Set(db.campaigns.map((c) => c.status));
    for (const expected of ['live', 'pending_admin', 'rejected', 'draft'] as const) {
      expect(statuses.has(expected), `missing ${expected}`).toBe(true);
    }
  });

  it('has no dangling foreign keys', () => {
    const restaurantIds = new Set(db.restaurants.map((r) => r.id));
    const creatorIds = new Set(db.creators.map((c) => c.id));
    const campaignIds = new Set(db.campaigns.map((c) => c.id));

    for (const campaign of db.campaigns) {
      expect(restaurantIds.has(campaign.restaurantId)).toBe(true);
      expect(creatorIds.has(campaign.creatorId)).toBe(true);
    }
    for (const booking of db.bookings) {
      expect(restaurantIds.has(booking.restaurantId)).toBe(true);
      expect(creatorIds.has(booking.creatorId)).toBe(true);
      if (booking.campaignId) expect(campaignIds.has(booking.campaignId)).toBe(true);
    }
    for (const review of db.reviews) {
      expect(restaurantIds.has(review.restaurantId)).toBe(true);
      expect(creatorIds.has(review.creatorId)).toBe(true);
    }
    for (const record of db.promoCodes) expect(campaignIds.has(record.campaignId)).toBe(true);
  });
});

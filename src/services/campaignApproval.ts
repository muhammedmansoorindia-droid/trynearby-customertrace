/**
 * Shared campaign-approval presentation used by both the admin and restaurant portals.
 *
 * <p>The dual-approval rule is the backend's: a campaign only reaches `ACTIVE` once *both* parties
 * have approved, and a single rejection makes it `REJECTED`. This module only *describes* that
 * state — it decides nothing, and it never invents a status the backend does not have.
 */

// Re-exported from the API layer rather than redeclared, so the sort offered in the UI and the sort
// sent to the backend cannot drift apart. This was the source of a compile error when the backend
// gained two orders and only the API-side type was widened.
import type { Campaign, CampaignSort } from '@/services/backend/campaigns';

export type { CampaignSort };

/** The two parties whose approval a campaign needs, in the order they must give it. */
export type ApprovalParty = 'admin' | 'restaurant';

export type DecisionOutcome = 'APPROVED' | 'REJECTED' | 'PENDING';

/**
 * One party's decision on a campaign, or `PENDING` when they have not acted yet.
 *
 * <p>Read from the backend's own fields. A campaign that has been rejected has the rejecting
 * party's decision recorded and the other's left null, so "no decision yet" and "never asked" are
 * the same `null` and are reported the same way.
 */
export function decisionOf(campaign: Campaign, party: ApprovalParty): DecisionOutcome {
  const value = party === 'admin' ? campaign.adminDecision : campaign.restaurantDecision;
  return value ?? 'PENDING';
}

/**
 * Which approval is still outstanding, phrased for the person reading it.
 *
 * <p>Only ever returns a stage for a campaign that is genuinely still pending; a live or rejected
 * campaign has no outstanding approval and reports `null`, so callers must not render a
 * "waiting on…" banner for it.
 */
export function awaitingApproval(campaign: Campaign): 'TryNearby' | 'restaurant' | null {
  if (campaign.status !== 'PENDING') return null;
  if (decisionOf(campaign, 'admin') === 'PENDING') return 'TryNearby';
  return 'restaurant';
}

/**
 * A one-line explanation of where a campaign sits in the two-approval flow.
 *
 * <p>Used as the card subtitle and in the detail view, so an approver can see at a glance whether
 * the decision is theirs to make or already waiting on someone else.
 */
export function approvalSummary(campaign: Campaign): string {
  const admin = decisionOf(campaign, 'admin');
  const restaurant = decisionOf(campaign, 'restaurant');

  if (admin === 'REJECTED' || restaurant === 'REJECTED') {
    const who = admin === 'REJECTED' ? 'TryNearby' : 'The restaurant';
    return `${who} rejected this campaign, so it cannot go live.`;
  }
  if (admin === 'PENDING') return 'Awaiting TryNearby review.';
  if (restaurant === 'PENDING') return 'TryNearby approved — awaiting the restaurant.';
  return 'Both approvals are in, so this campaign is live.';
}

/** Sort orders offered in the UI, with the label each button shows. */
export const SORT_OPTIONS: ReadonlyArray<{ id: CampaignSort; label: string }> = [
  { id: 'newest', label: 'Newest first' },
  { id: 'oldest', label: 'Oldest first' },
  { id: 'updated', label: 'Recently updated' },
  { id: 'target', label: 'Target customers' },
];
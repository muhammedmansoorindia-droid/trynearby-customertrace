/**
 * Campaign lifecycle.
 *
 *   draft ──submit──▶ pending_admin ──approve──▶ approved ──go live──▶ live
 *                          │                        │                   │
 *                          └──reject───▶ rejected  └──reject──▶ rejected└─▶ completed
 *
 * A rejected campaign may be revised and resubmitted, returning to `pending_admin`.
 *
 * Kept as an explicit transition table (rather than ad-hoc `if` checks in components) so the
 * rules are testable in isolation and an illegal transition fails loudly instead of silently
 * mutating state.
 */

import type { Campaign, CampaignStatus, Portal } from '@/types/models';

/** Statuses that can accept customers. */
export const REDEEMABLE_STATUSES: readonly CampaignStatus[] = ['approved', 'live'];

/** Terminal statuses — no further transitions permitted. */
export const TERMINAL_STATUSES: readonly CampaignStatus[] = ['completed', 'cancelled'];

export type CampaignAction =
  | 'submit'
  | 'approve'
  | 'reject'
  | 'go_live'
  | 'complete'
  | 'cancel'
  | 'reopen';

/** Which portal may perform each action. */
const ACTION_OWNER: Record<CampaignAction, Portal> = {
  submit: 'creator',
  approve: 'admin',
  reject: 'admin',
  go_live: 'creator',
  complete: 'admin',
  cancel: 'admin',
  reopen: 'creator',
};

/** The full legal transition table. */
const TRANSITIONS: Record<CampaignAction, { from: CampaignStatus[]; to: CampaignStatus }> = {
  submit: { from: ['draft', 'rejected'], to: 'pending_admin' },
  approve: { from: ['pending_admin'], to: 'approved' },
  reject: { from: ['pending_admin', 'approved'], to: 'rejected' },
  go_live: { from: ['approved'], to: 'live' },
  complete: { from: ['live', 'approved'], to: 'completed' },
  cancel: { from: ['draft', 'pending_admin', 'approved', 'live'], to: 'cancelled' },
  reopen: { from: ['rejected', 'cancelled', 'completed'], to: 'draft' },
};

export interface TransitionCheck {
  allowed: boolean;
  to: CampaignStatus;
  /** Present when `allowed` is false — why the transition was refused. */
  reason?: string;
}

export function canPerform(status: CampaignStatus, action: CampaignAction): boolean {
  return TRANSITIONS[action].from.includes(status);
}

/**
 * Resolve the result of an action.
 *
 * Returns `allowed: false` rather than throwing so callers can render an inline message; the
 * store additionally refuses to apply a transition it was handed.
 */
export function checkTransition(status: CampaignStatus, action: CampaignAction): TransitionCheck {
  const rule = TRANSITIONS[action];
  if (TERMINAL_STATUSES.includes(status) && action !== 'reopen') {
    return {
      allowed: false,
      to: status,
      reason: `A ${status.replace('_', ' ')} campaign cannot be ${action.replace('_', ' ')}ed.`,
    };
  }
  if (!rule.from.includes(status)) {
    return {
      allowed: false,
      to: status,
      reason: `Cannot ${action.replace('_', ' ')} a campaign that is ${status.replace('_', ' ')}.`,
    };
  }
  return { allowed: true, to: rule.to };
}

export function actionOwner(action: CampaignAction): Portal {
  return ACTION_OWNER[action];
}

/** Apply a transition, returning a new campaign. Caller must check `canPerform` first. */
export function applyTransition(campaign: Campaign, action: CampaignAction, now: string): Campaign {
  const check = checkTransition(campaign.status, action);
  if (!check.allowed) return campaign;

  const next: Campaign = { ...campaign, status: check.to };

  switch (action) {
    case 'submit':
      next.submittedAt = now;
      // Resubmitting clears a previous rejection.
      next.rejectionReason = undefined;
      next.decidedAt = undefined;
      next.decidedByAdminId = undefined;
      break;
    case 'approve':
      next.decidedAt = now;
      next.rejectionReason = undefined;
      break;
    case 'reject':
      next.decidedAt = now;
      break;
    case 'go_live':
      next.decidedAt = next.decidedAt ?? now;
      break;
    case 'cancel':
      next.decidedAt = now;
      break;
    default:
      break;
  }

  return next;
}

/** Human label for a status, used on badges and filters. */
export const STATUS_LABELS: Record<CampaignStatus, string> = {
  draft: 'Draft',
  pending_admin: 'Pending Review',
  approved: 'Approved',
  rejected: 'Rejected',
  live: 'Live',
  completed: 'Completed',
  cancelled: 'Cancelled',
};

/** Tailwind classes per status, matching the Stitch badge language. */
export const STATUS_TONES: Record<CampaignStatus, string> = {
  draft: 'bg-surface-container text-on-surface-variant border-outline-variant/40',
  pending_admin: 'bg-primary-fixed text-on-primary-fixed-variant border-primary-fixed',
  approved: 'bg-surface-container text-primary border-primary/25',
  live: 'bg-primary text-on-primary border-primary',
  rejected: 'bg-error-container text-on-error-container border-error/25',
  completed: 'bg-secondary-container text-on-secondary-fixed border-secondary/25',
  cancelled: 'bg-surface-dim text-outline border-outline/25',
};
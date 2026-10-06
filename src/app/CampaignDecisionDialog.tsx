/**
 * Modal confirmation for a campaign decision.
 *
 * <p>Approving or rejecting a campaign is not undoable from this screen, so both actions confirm
 * first and show exactly what is about to be approved or rejected: the code, venue, creator and
 * discount. The dialog deliberately repeats the backend's own fields rather than inventing a
 * summary, so what the approver confirms is what the server will act on.
 *
 * <p>Rejection additionally requires a reason. The backend caps it at 400 characters but does not
 * insist on one; the creator reads this text verbatim, so requiring it here is a deliberate product
 * rule rather than an invented constraint.
 */
import { useState, type ReactNode } from 'react';

import { Button, Field, inputClass } from '@/app/ui';
import type { Campaign } from '@/services/backend/campaigns';
import { cn } from '@/utils';
import { formatDateRange } from '@/utils/format';

/** Mirrors the backend's `@Size(max = 400)` on a decision reason. */
export const MAX_REASON_LENGTH = 400;

export interface DecisionDialogProps {
  campaign: Campaign;
  decision: 'approve' | 'reject';
  pending: boolean;
  /** Shown when the server refuses, so the dialog explains rather than just closing. */
  error?: string | null;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
}

export function CampaignDecisionDialog({
  campaign,
  decision,
  pending,
  error,
  onConfirm,
  onCancel,
}: DecisionDialogProps) {
  const approving = decision === 'approve';
  const title = approving ? 'Approve this campaign?' : 'Reject this campaign?';
  const [reason, setReason] = useState('');
  // A trimmed reason is required to reject: whitespace alone is not an explanation.
  const reasonMissing = !approving && reason.trim().length === 0;

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-scrim/60 p-gutter sm:items-center"
      role="presentation"
      onClick={(event) => {
        // Clicking the backdrop cancels, but not while a decision is in flight.
        if (event.target === event.currentTarget && !pending) onCancel();
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="decision-dialog-title"
        className="w-full max-w-lg rounded-2xl bg-surface p-gutter shadow-lg"
      >
        <h2 id="decision-dialog-title" className="font-headline-sm text-headline-sm text-on-surface">
          {title}
        </h2>
        <p className="mt-1 text-body-sm text-body-sm text-on-surface-variant">
          {approving
            ? 'The campaign moves to the next approval step.'
            : 'The campaign stops here and the creator sees your reason.'}
        </p>

        {/* The facts being confirmed, read straight off the record under review. */}
        <dl className="mt-space-md space-y-space-xs rounded-xl bg-surface-container-lowest p-space-sm text-body-sm text-body-sm">
          <Row label="Campaign" value={<span className="font-mono">{campaign.title}</span>} />
          <Row label="Restaurant" value={campaign.restaurantName ?? '—'} />
          <Row label="Creator" value={campaign.creatorName ?? '—'} />
          <Row label="Discount" value={`${campaign.discountPercent}%`} />
          <Row label="Dates" value={formatDateRange(campaign.startDate, campaign.endDate)} />
        </dl>

        {!approving ? (
          <div className="mt-space-md">
            <Field
              label="Reason for rejection"
              hint={`The creator sees this verbatim. ${reason.trim().length}/${MAX_REASON_LENGTH} characters.`}
              error={reasonMissing ? 'A reason is required.' : undefined}
            >
              <textarea
                className={cn(inputClass, 'min-h-20 resize-y')}
                value={reason}
                maxLength={MAX_REASON_LENGTH}
                autoFocus
                aria-label="Reason for rejection"
                onChange={(event) => setReason(event.target.value)}
                placeholder="e.g. The offer cannot be honoured at settlement."
              />
            </Field>
          </div>
        ) : null}

        {error ? (
          <p role="alert" className="mt-space-sm text-body-sm text-body-sm text-error">
            {error}
          </p>
        ) : null}

        <div className="mt-gutter flex flex-wrap justify-end gap-space-sm">
          <Button variant="ghost" onClick={onCancel} disabled={pending}>
            Cancel
          </Button>
          <Button
            variant={approving ? 'primary' : 'danger'}
            onClick={() => onConfirm(reason.trim())}
            disabled={pending || reasonMissing}
          >
            {pending
              ? approving
                ? 'Approving…'
                : 'Rejecting…'
              : approving
                ? 'Approve campaign'
                : 'Reject campaign'}
          </Button>
        </div>
      </div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex gap-space-sm">
      <dt className="w-28 shrink-0 text-on-surface-variant">{label}</dt>
      <dd className="min-w-0 text-on-surface">{value}</dd>
    </div>
  );
}
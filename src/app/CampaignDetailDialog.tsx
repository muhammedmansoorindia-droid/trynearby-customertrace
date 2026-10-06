/**
 * Campaign detail view, with the decision controls an approver needs.
 *
 * <p>Shows both parties' decisions and the approval history, so an approver can see how a campaign
 * reached its current state without leaving the queue.
 *
 * <p>Decision buttons appear here when — and only when — the backend's own {@code canApprove} flag
 * says this caller may decide for their party right now. That flag is computed server-side from the
 * campaign status, the caller's role and (for a restaurant) ownership, so this component never
 * re-derives eligibility and cannot offer a decision the server would refuse. A campaign already
 * approved or rejected renders its resulting state instead of a button.
 *
 * <p>Pressing a button does not decide anything here: it hands off to the caller's confirmation
 * dialog, so there is exactly one place a decision is actually made.
 */
import { useCallback } from 'react';

import { useResource } from '@/app/useResource';
import { Badge, Button, ErrorState, LoadingState } from '@/app/ui';
import { campaignsApi, toFrontendStatus, type Campaign } from '@/services/backend/campaigns';
import { approvalSummary, decisionOf } from '@/services/campaignApproval';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { formatDateRange, formatRelative } from '@/utils/format';

export interface CampaignDetailDialogProps {
  campaign: Campaign;
  /** Loads the history. Admin and restaurant use different, equally authorised routes. */
  loadHistory: (id: number) => Promise<import('@/services/backend/campaigns').StatusHistoryEntry[]>;
  /**
   * Re-reads the campaign this modal is showing, from the caller's own detail route.
   *
   * <p>The list row the modal was opened from is a snapshot, and a stale snapshot is exactly how a
   * reviewer ends up looking at a campaign that has since been decided (or, as happened, at one
   * whose `canApprove` was never correct). Refetching means the decision controls and the displayed
   * states both come from the server at the moment the reviewer opens the record.
   */
  loadCampaign?: (id: number) => Promise<Campaign>;
  onClose: () => void;
  /**
   * Opens the caller's confirmation dialog for a decision.
   *
   * <p>Omitted by a read-only caller (a creator viewing their own campaign), which is why the
   * actions below are conditional rather than merely disabled.
   */
  onDecide?: (kind: 'approve' | 'reject') => void;
  /** True while a decision for this campaign is in flight. */
  pending?: boolean;
  /** Names the party in the action copy, so the wording matches the portal it is shown in. */
  party?: 'admin' | 'restaurant';
}

export function CampaignDetailDialog({
  campaign,
  loadHistory,
  loadCampaign,
  onClose,
  onDecide,
  pending = false,
  party = 'admin',
}: CampaignDetailDialogProps) {
  const history = useResource(useCallback(() => loadHistory(campaign.id), [loadHistory, campaign.id]), [
    campaign.id,
  ]);

  // The server's current copy of this campaign, which supersedes the list snapshot. Falls back to
  // the row we were given if the refetch fails, so a transient error cannot blank the modal.
  const fresh = useResource(
    useCallback(() => (loadCampaign ? loadCampaign(campaign.id) : Promise.resolve(campaign)), [
      loadCampaign,
      campaign,
    ]),
    [campaign.id, loadCampaign],
  );
  const shown = fresh.data ?? campaign;
  const status = toFrontendStatus(shown.status);

  // This party's own outcome, so an approver sees "you approved this" rather than a dead button.
  const myDecision = decisionOf(shown, party);
  const decidedState =
    shown.canApprove || myDecision === 'PENDING'
      ? null
      : myDecision === 'APPROVED'
        ? party === 'admin'
          ? 'TryNearby approved this shown.'
          : 'The restaurant approved this shown.'
        : party === 'admin'
          ? 'TryNearby rejected this shown.'
          : 'The restaurant rejected this shown.';

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-scrim/60 p-gutter sm:items-center"
      role="presentation"
      onClick={(event) => {
        // Backdrop click closes; a click inside the panel must not.
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="campaign-detail-title"
        className="max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-2xl bg-surface p-gutter"
      >
        <div className="flex items-start justify-between gap-space-sm">
          <h2 id="campaign-detail-title" className="font-headline-sm text-headline-sm text-on-surface">
            <span className="font-mono">{shown.title}</span>
          </h2>
          <Badge className={STATUS_TONES[status]}>{STATUS_LABELS[status]}</Badge>
        </div>
        <p className="mt-1 text-body-sm text-body-sm text-on-surface-variant">
          {approvalSummary(campaign)}
        </p>

        <dl className="mt-space-md grid grid-cols-2 gap-space-sm text-body-sm text-body-sm">
          <Detail label="Creator" value={shown.creatorName ?? '—'} />
          <Detail label="Restaurant" value={shown.restaurantName ?? '—'} />
          <Detail label="Discount" value={`${shown.discountPercent}%`} />
          <Detail label="Target customers" value={String(shown.targetCustomers)} />
          <Detail label="Duration" value={`${shown.durationDays} days`} />
          <Detail label="Dates" value={formatDateRange(shown.startDate, shown.endDate)} />
          <Detail label="TryNearby decision" value={decisionOf(shown, 'admin')} />
          <Detail label="Restaurant decision" value={decisionOf(shown, 'restaurant')} />
          <Detail label="Created" value={formatRelative(shown.createdAt)} />
          <Detail
            label="Submitted"
            value={shown.submittedAt ? formatRelative(shown.submittedAt) : 'Not submitted'}
          />
          <Detail label="Offer" value={shown.offerText} />
          <Detail label="QR pass" value={shown.promoCode ?? 'Issued when the campaign goes live'} />
        </dl>

        {shown.adminReason ? (
          <Reason label="TryNearby" text={shown.adminReason} />
        ) : null}
        {shown.restaurantReason ? (
          <Reason label="Restaurant" text={shown.restaurantReason} />
        ) : null}

        <h3 className="mt-gutter font-label-md text-label-md text-on-surface">Approval history</h3>
        {history.loading ? (
          <LoadingState label="Loading history…" />
        ) : history.error ? (
          <ErrorState
            message={history.error.message}
            isNetworkError={history.error.isNetworkError}
            onRetry={history.reload}
          />
        ) : !history.data?.length ? (
          <p className="text-body-sm text-body-sm text-on-surface-variant">
            No status changes recorded yet.
          </p>
        ) : (
          <ol className="mt-space-sm space-y-space-xs">
            {history.data.map((entry) => (
              <li key={entry.id} className="text-body-sm text-body-sm">
                <span className="text-on-surface">{entry.fromStatus ?? 'created'}</span>
                {' → '}
                <span className="text-on-surface">{entry.toStatus}</span>
                <span className="text-on-surface-variant">
                  {' · '}
                  {formatRelative(entry.changedAt)}
                  {entry.reason ? ` · ${entry.reason}` : ''}
                </span>
              </li>
            ))}
          </ol>
        )}

        <div className="mt-gutter flex flex-wrap items-center justify-end gap-space-sm border-t border-outline-variant/30 pt-gutter">
          {/*
            The outcome line, so a decided campaign explains itself instead of offering a button
            that would be refused. Only the caller's own party is described.
          */}
          {decidedState ? (
            <p className="mr-auto text-body-sm text-body-sm text-on-surface-variant">
              {decidedState}
            </p>
          ) : null}
          <Button variant="ghost" onClick={onClose}>
            Close
          </Button>
          {/*
            Offered only when the backend says this caller may decide right now. A read-only caller
            passes no `onDecide`, and an already-decided campaign leaves nothing to decide.
          */}
          {onDecide && shown.canApprove ? (
            <>
              <Button variant="secondary" onClick={() => onDecide('reject')} disabled={pending}>
                Reject campaign
              </Button>
              <Button onClick={() => onDecide('approve')} disabled={pending}>
                Approve campaign
              </Button>
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-on-surface-variant">{label}</dt>
      <dd className="text-on-surface">{value}</dd>
    </div>
  );
}

function Reason({ label, text }: { label: string; text: string }) {
  return (
    <p className="mt-space-sm rounded-lg bg-error-container px-space-sm py-space-xs text-body-sm text-body-sm text-on-error-container">
      {label}: {text}
    </p>
  );
}

/** Convenience wrapper so callers pass the id rather than the loader itself. */
export const adminHistoryLoader = campaignsApi.historyForAdmin;
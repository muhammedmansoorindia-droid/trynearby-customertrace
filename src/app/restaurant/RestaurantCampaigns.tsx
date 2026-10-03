/**
 * Campaigns running at the restaurant's venue.
 *
 * <p>The restaurant's half of the dual-approval workflow, plus per-campaign quota consumption.
 * Counts come from the backend's report endpoint, so the numbers here always agree with the
 * platform dashboard rather than being recomputed from a local event log.
 */

import { useCallback, useState } from 'react';

import { useMutation, useResource } from '@/app/useResource';
import {
  Badge,
  Button,
  Callout,
  Card,
  EmptyState,
  ErrorState,
  LoadingState,
  SectionHeading,
} from '@/app/ui';
import { campaignsApi, toFrontendStatus, type Campaign } from '@/services/backend/campaigns';
import { reportsApi, type CampaignPerformance } from '@/services/backend/reports';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { formatCompactINR, formatDateRange } from '@/utils/format';

export function RestaurantCampaigns() {
  const [rejecting, setRejecting] = useState<number | null>(null);
  const [reason, setReason] = useState('');

  const list = useResource(useCallback(() => campaignsApi.listForRestaurant(), []), []);
  const performance = useResource(useCallback(() => reportsApi.restaurantCampaigns(), []), []);

  const decide = useMutation(async (id: number, decision: 'approve' | 'reject', why?: string) =>
    decision === 'approve'
      ? campaignsApi.approveAsRestaurant(id, why)
      : campaignsApi.rejectAsRestaurant(id, why),
  );

  const refresh = () => {
    list.reload();
    performance.reload();
  };

  const campaigns = list.data?.content ?? [];
  // Keyed by campaign id so each card shows its own consumption.
  const byId = new Map((performance.data ?? []).map((row) => [row.id, row]));

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Campaigns at your venue"
        subtitle="Offers creators have run here, with the quota consumed so far."
        action={
          <Button variant="secondary" onClick={refresh}>
            Refresh
          </Button>
        }
      />

      {decide.error ? <Callout tone="error">{decide.error.message}</Callout> : null}

      {list.error ? (
        <ErrorState
          message={list.error.message}
          isNetworkError={list.error.isNetworkError}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <LoadingState label="Loading campaigns…" />
      ) : campaigns.length === 0 ? (
        <EmptyState
          title="No campaigns yet"
          hint="When a creator submits a campaign for your venue it will appear here."
        />
      ) : (
        <ul className="grid gap-space-md md:grid-cols-2">
          {campaigns.map((campaign) => (
            <CampaignCard
              key={campaign.id}
              campaign={campaign}
              metrics={byId.get(campaign.id)}
              rejecting={rejecting === campaign.id}
              reason={reason}
              pending={decide.pending}
              onReasonChange={setReason}
              onToggleReject={() => {
                setRejecting(rejecting === campaign.id ? null : campaign.id);
                setReason('');
              }}
              onCancelReject={() => setRejecting(null)}
              onDecide={async (decision, why) => {
                const result = await decide.run(campaign.id, decision, why);
                if (!result) return;
                if (decision === 'reject') {
                  setRejecting(null);
                  setReason('');
                }
                refresh();
              }}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

interface CampaignCardProps {
  campaign: Campaign;
  metrics: CampaignPerformance | undefined;
  rejecting: boolean;
  reason: string;
  pending: boolean;
  onReasonChange: (value: string) => void;
  onToggleReject: () => void;
  onCancelReject: () => void;
  onDecide: (decision: 'approve' | 'reject', reason?: string) => Promise<void>;
}

function CampaignCard({
  campaign,
  metrics,
  rejecting,
  reason,
  pending,
  onReasonChange,
  onToggleReject,
  onCancelReject,
  onDecide,
}: CampaignCardProps) {
  const status = toFrontendStatus(campaign.status);
  const used = Math.min(
    100,
    metrics && campaign.targetCustomers > 0
      ? (metrics.verifiedCustomers / campaign.targetCustomers) * 100
      : 0,
  );

  return (
    <Card as="li" className="p-gutter">
      <div className="flex flex-wrap items-start justify-between gap-space-sm">
        <div>
          <h3 className="font-headline-sm text-headline-sm text-on-surface">{campaign.title}</h3>
          <p className="text-body-sm text-body-sm text-on-surface-variant">
            by {campaign.creatorName}
          </p>
        </div>
        <Badge className={STATUS_TONES[status]}>{STATUS_LABELS[status]}</Badge>
      </div>

      <p className="text-body-md text-body-md text-on-surface mt-space-sm">{campaign.offerText}</p>
      <p className="text-body-sm text-body-sm text-on-surface-variant">
        {formatDateRange(campaign.startDate, campaign.endDate)} ·{' '}
        <span className="font-mono">{campaign.promoCode ?? '—'}</span>
      </p>

      <div className="mt-space-md">
        <div className="flex items-center justify-between text-body-sm text-body-sm">
          <span className="text-on-surface-variant">Quota consumed</span>
          <span className="text-on-surface">
            {metrics?.verifiedCustomers ?? 0} / {campaign.targetCustomers}
          </span>
        </div>
        <div className="mt-1 h-2 rounded-full bg-surface-container overflow-hidden">
          <div className="h-full rounded-full bg-primary" style={{ width: `${used}%` }} />
        </div>
      </div>

      <dl className="mt-space-md grid grid-cols-3 gap-space-sm text-body-sm text-body-sm">
        <div>
          <dt className="text-on-surface-variant">Scans</dt>
          <dd className="text-on-surface">{metrics?.scans ?? 0}</dd>
        </div>
        <div>
          <dt className="text-on-surface-variant">Customers</dt>
          <dd className="text-on-surface">{metrics?.verifiedCustomers ?? 0}</dd>
        </div>
        <div>
          <dt className="text-on-surface-variant">Discount</dt>
          <dd className="text-on-surface">{formatCompactINR(metrics?.discountPaise ?? 0)}</dd>
        </div>
      </dl>

      {/* The backend decides whether the restaurant's decision is legal for this status. */}
      {campaign.canApprove ? (
        <div className="mt-space-md flex flex-wrap gap-space-sm border-t border-outline-variant/30 pt-space-md">
          <Button disabled={pending} onClick={() => onDecide('approve')}>
            Approve
          </Button>
          <Button variant="secondary" disabled={pending} onClick={onToggleReject}>
            Reject
          </Button>
        </div>
      ) : null}

      {rejecting ? (
        <form
          className="mt-space-md space-y-space-sm"
          onSubmit={(event) => {
            event.preventDefault();
            void onDecide('reject', reason.trim());
          }}
        >
          <label className="block">
            <span className="block font-label-md text-label-md text-on-surface mb-1">
              Reason for rejection
            </span>
            <textarea
              className="w-full min-h-20 resize-y rounded-lg border border-outline-variant/50 bg-surface-container-lowest px-space-sm py-space-sm text-body-md text-body-md text-on-surface focus-ring"
              value={reason}
              onChange={(event) => onReasonChange(event.target.value)}
              placeholder="e.g. We cannot honour this offer at peak hours."
            />
          </label>
          <div className="flex gap-space-sm">
            <Button type="submit" variant="danger" disabled={pending}>
              Confirm rejection
            </Button>
            <Button variant="ghost" onClick={onCancelReject}>
              Cancel
            </Button>
          </div>
        </form>
      ) : null}
    </Card>
  );
}

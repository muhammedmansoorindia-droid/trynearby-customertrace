/**
 * Campaign requests: the restaurant's half of the dual-approval queue.
 *
 * <p>The list is always scoped to the signed-in restaurant by the backend, which resolves the venue
 * from the access token rather than from the request — so there is no parameter here that could ask
 * for another venue's campaigns, and the server would refuse one anyway.
 *
 * <p>Counts come from the backend's report endpoint, so the numbers here always agree with the
 * platform dashboard rather than being recomputed from a local event log.
 */
import { useCallback, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import { CampaignDecisionDialog } from '@/app/CampaignDecisionDialog';
import { CampaignDetailDialog } from '@/app/CampaignDetailDialog';
import { useMutation, useResource } from '@/app/useResource';
import {
  Badge,
  Button,
  Callout,
  Card,
  EmptyState,
  ErrorState,
  Field,
  FunnelBar,
  LoadingState,
  SectionHeading,
  inputClass,
} from '@/app/ui';
import {
  campaignsApi,
  toFrontendStatus,
  type Campaign,
  type CampaignSort,
} from '@/services/backend/campaigns';
import { reportsApi, type CampaignPerformance } from '@/services/backend/reports';
import { approvalSummary, decisionOf, SORT_OPTIONS } from '@/services/campaignApproval';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { cn } from '@/utils';
import { formatCompactINR, formatDateRange, formatRelative } from '@/utils/format';

/**
 * Status tabs.
 *
 * <p>Only statuses the backend actually has. A `DRAFT` is private to its creator until submitted,
 * so a restaurant never sees one and it earns no tab here.
 */
const FILTERS = [
  { id: 'PENDING', label: 'Pending approval' },
  { id: 'ACTIVE', label: 'Live' },
  { id: 'REJECTED', label: 'Rejected' },
  { id: '', label: 'All' },
] as const;

export function RestaurantCampaigns() {
  // The dashboard's "Campaign requests" link deep-links into the pending queue.
  const [params, setParams] = useSearchParams();
  const filter = params.get('status') ?? 'PENDING';
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<CampaignSort>('newest');
  const [detail, setDetail] = useState<Campaign | null>(null);
  const [decision, setDecision] = useState<{
    campaign: Campaign;
    kind: 'approve' | 'reject';
  } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const list = useResource(
    useCallback(
      () =>
        campaignsApi.listForRestaurant({
          ...(filter ? { status: filter } : {}),
          search,
          sort,
        }),
      [filter, search, sort],
    ),
    [filter, search, sort],
  );
  const performance = useResource(useCallback(() => reportsApi.restaurantCampaigns(), []), []);

  const decide = useMutation(async (id: number, decision: 'approve' | 'reject', why?: string) =>
    decision === 'approve'
      ? campaignsApi.approveAsRestaurant(id, why)
      : campaignsApi.rejectAsRestaurant(id, why),
  );

  const refresh = () => {
    list.reload();
    performance.reload();
    setDetail(null);
  };

  const campaigns = list.data?.content ?? [];
  // Keyed by campaign id so each card shows its own consumption.
  const byId = new Map((performance.data ?? []).map((row) => [row.id, row]));

  /** Applies a decision and reports it only after the backend has confirmed it. */
  const confirmDecision = async (reason: string) => {
    if (!decision) return;
    const { campaign, kind } = decision;
    const result = await decide.run(campaign.id, kind, reason || undefined);
    if (!result) return; // The dialog stays open, showing the server's message.
    setNotice(
      kind === 'approve'
        ? `${campaign.title} approved. It is now live.`
        : `${campaign.title} rejected. The creator has been told why.`,
    );
    setDecision(null);
    refresh();
  };

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Campaign requests"
        subtitle="Offers creators want to run at your venue. A campaign goes live only once you approve it."
        action={
          <Button variant="secondary" onClick={refresh}>
            Refresh
          </Button>
        }
      />

      {/* Search and ordering, answered by the database rather than by filtering the page in hand. */}
      <div className="flex flex-wrap items-end gap-space-md">
        <div className="min-w-56 flex-1">
          <Field label="Search" hint="Matches the campaign code or the creator.">
            <input
              className={inputClass}
              type="search"
              value={search}
              placeholder="e.g. a campaign code or creator name"
              aria-label="Search campaign requests"
              onChange={(event) => setSearch(event.target.value)}
            />
          </Field>
        </div>
        <div className="w-48">
          <Field label="Order by">
            <select
              className={inputClass}
              value={sort}
              aria-label="Sort campaign requests"
              onChange={(event) => setSort(event.target.value as CampaignSort)}
            >
              {SORT_OPTIONS.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.label}
                </option>
              ))}
            </select>
          </Field>
        </div>
      </div>

      <div role="tablist" aria-label="Filter requests" className="ct-scroll-x flex gap-space-xs">
        {FILTERS.map((option) => {
          const selected = filter === option.id;
          return (
            <button
              key={option.label}
              role="tab"
              aria-selected={selected}
              type="button"
              onClick={() => {
                const next = new URLSearchParams(params);
                if (option.id) next.set('status', option.id);
                else next.delete('status');
                setParams(next, { replace: true });
              }}
              className={cn(
                'whitespace-nowrap rounded-full border px-space-md py-space-sm font-label-sm text-label-sm transition-colors',
                selected
                  ? 'bg-primary text-on-primary border-primary'
                  : 'bg-surface-container-lowest text-on-surface-variant border-outline-variant/40 hover:bg-surface-container-high',
              )}
            >
              {option.label}
            </button>
          );
        })}
      </div>

      {notice ? <Callout tone="success">{notice}</Callout> : null}

      {list.error ? (
        <ErrorState
          message={list.error.message}
          isNetworkError={list.error.isNetworkError}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <LoadingState label="Loading campaign requests…" />
      ) : campaigns.length === 0 ? (
        <EmptyState
          title={
            search.trim()
              ? 'No campaigns match that search'
              : filter === 'PENDING'
                ? 'No campaigns awaiting your approval'
                : 'No campaigns here'
          }
          hint={
            search.trim()
              ? `Nothing matches “${search.trim()}”. Try a different term.`
              : filter === 'PENDING'
                ? 'Nothing needs a decision right now.'
                : 'When a creator submits a campaign for your venue it will appear here.'
          }
        />
      ) : (
        <ul className="grid gap-space-md md:grid-cols-2">
          {campaigns.map((campaign) => (
            <CampaignCard
              key={campaign.id}
              campaign={campaign}
              metrics={byId.get(campaign.id)}
              pending={decide.pending}
              onApprove={() => setDecision({ campaign, kind: 'approve' })}
              onReject={() => setDecision({ campaign, kind: 'reject' })}
              onView={() => setDetail(campaign)}
            />
          ))}
        </ul>
      )}

      {decision ? (
        <CampaignDecisionDialog
          campaign={decision.campaign}
          decision={decision.kind}
          pending={decide.pending}
          error={decide.error?.message ?? null}
          onConfirm={confirmDecision}
          onCancel={() => setDecision(null)}
        />
      ) : null}

      {detail ? (
        <CampaignDetailDialog
          campaign={detail}
          loadHistory={campaignsApi.historyForRestaurant}
          loadCampaign={campaignsApi.detailForRestaurant}
          party="restaurant"
          pending={decide.pending}
          onDecide={(kind) => setDecision({ campaign: detail, kind })}
          onClose={() => setDetail(null)}
        />
      ) : null}
    </div>
  );
}

interface CampaignCardProps {
  campaign: Campaign;
  metrics: CampaignPerformance | undefined;
  pending: boolean;
  onApprove: () => void;
  onReject: () => void;
  onView: () => void;
}

function CampaignCard({ campaign, metrics, pending, onApprove, onReject, onView }: CampaignCardProps) {
  const status = toFrontendStatus(campaign.status);
  const used =
    campaign.targetCustomers > 0
      ? Math.min(100, ((metrics?.verifiedCustomers ?? 0) / campaign.targetCustomers) * 100)
      : 0;

  return (
    <Card className="p-gutter">
      <div className="flex items-start justify-between gap-space-sm">
        <div className="min-w-0">
          <h3 className="font-headline-sm text-headline-sm text-on-surface font-mono">
            {campaign.title}
          </h3>
          <p className="text-body-sm text-body-sm text-on-surface-variant">
            by {campaign.creatorName}
          </p>
        </div>
        <Badge className={STATUS_TONES[status]}>{STATUS_LABELS[status]}</Badge>
      </div>

      {/* Both approvals shown separately, so it is always clear whose turn it is. */}
      <dl className="mt-space-sm grid grid-cols-2 gap-space-xs text-body-sm text-body-sm">
        <div>
          <dt className="text-on-surface-variant">TryNearby</dt>
          <dd className="text-on-surface">{decisionOf(campaign, 'admin')}</dd>
        </div>
        <div>
          <dt className="text-on-surface-variant">Your approval</dt>
          <dd className="text-on-surface">{decisionOf(campaign, 'restaurant')}</dd>
        </div>
      </dl>
      <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">
        {approvalSummary(campaign)}
      </p>

      <p className="text-body-md text-body-md text-on-surface mt-space-sm">{campaign.offerText}</p>
      <p className="text-body-sm text-body-sm text-on-surface-variant">
        {formatDateRange(campaign.startDate, campaign.endDate)} ·{' '}
        <span className="font-mono">{campaign.promoCode ?? '—'}</span>
      </p>
      <p className="text-body-sm text-body-sm text-on-surface-variant">
        {campaign.discountPercent}% off · {campaign.targetCustomers} customers ·{' '}
        {campaign.durationDays} days
        {campaign.submittedAt ? ` · submitted ${formatRelative(campaign.submittedAt)}` : ''}
      </p>

      {campaign.restaurantReason ? (
        <p className="mt-space-sm rounded-lg bg-error-container px-space-sm py-space-xs text-body-sm text-body-sm text-on-error-container">
          {campaign.restaurantReason}
        </p>
      ) : null}

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

      <div className="mt-space-md">
        <FunnelBar
          scans={metrics?.scans ?? 0}
          redemptions={metrics?.redemptions ?? 0}
          customers={metrics?.verifiedCustomers ?? 0}
        />
        <p className="mt-1 text-body-sm text-body-sm text-on-surface-variant">
          Discount settled {formatCompactINR(metrics?.discountPaise ?? 0)}
        </p>
      </div>

      {/* The backend decides whether the restaurant's decision is legal for this status. */}
      {campaign.canApprove ? (
        <div className="mt-space-md flex flex-wrap gap-space-sm border-t border-outline-variant/30 pt-space-md">
          <Button disabled={pending} onClick={onApprove}>
            Approve
          </Button>
          <Button variant="secondary" disabled={pending} onClick={onReject}>
            Reject
          </Button>
        </div>
      ) : null}

      <div className="mt-2">
        <Button variant="ghost" onClick={onView}>
          View details
        </Button>
      </div>
    </Card>
  );
}
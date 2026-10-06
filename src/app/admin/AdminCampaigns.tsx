/**
 * Admin campaign queue: review, search and decide.
 *
 * <p>Decisions are sent to the backend and the list is refetched only after the backend confirms,
 * so the screen can never show an approval the server refused. Which actions are offered comes from
 * the backend's own `canApprove` flag rather than a client-side guess.
 *
 * <p>Search, status filtering and ordering all run in the database, so the queue is correct however
 * many campaigns there are. The tabs' counts come from the unfiltered list, so a badge never reads
 * "0" merely because a filter is currently on.
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
import {
  approvalSummary,
  decisionOf,
  SORT_OPTIONS,
} from '@/services/campaignApproval';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { cn } from '@/utils';
import { formatDateRange, formatRelative } from '@/utils/format';

/**
 * Status tabs, mapped one-to-one onto backend statuses.
 *
 * <p>Only states the backend actually has are listed. `CANCELLED` and `COMPLETED` are reachable but
 * are terminal, so they appear under "All" rather than earning a tab of their own.
 */
const FILTERS = [
  { id: 'PENDING', label: 'Awaiting review' },
  { id: 'ACTIVE', label: 'Live' },
  { id: 'REJECTED', label: 'Rejected' },
  { id: 'DRAFT', label: 'Draft' },
  { id: '', label: 'All' },
] as const;

/**
 * Empty-state copy.
 *
 * <p>Distinguishes "nothing is waiting for you" from "your search matched nothing", because those
 * need different reactions: the first is good news, the second is a typo to fix.
 */
function emptyTitle(filter: string, search: string): string {
  if (search.trim()) return 'No campaigns match that search';
  if (filter === 'PENDING') return 'No campaigns awaiting review';
  if (filter === '') return 'No campaigns on record';
  return 'No campaigns in this view';
}

function emptyHint(filter: string, search: string): string {
  if (search.trim()) return `Nothing matches “${search.trim()}”. Try a different term or clear the search.`;
  if (filter === 'PENDING') return 'The review queue is clear. Nothing needs a decision right now.';
  return 'Try a different filter above.';
}

export function AdminCampaigns() {
  // The review-queue link lands on ?status=PENDING, so the queue opens where the user is going.
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
        campaignsApi.listForAdmin({
          ...(filter ? { status: filter } : {}),
          search,
          sort,
        }),
      [filter, search, sort],
    ),
    [filter, search, sort],
  );

  // Counts come from the unfiltered list, so tab badges never read as "0" while a filter is on.
  const counts = useResource(useCallback(() => campaignsApi.listForAdmin(), []), []);

  const decide = useMutation(async (id: number, decision: 'approve' | 'reject', why?: string) =>
    decision === 'approve'
      ? campaignsApi.approveAsAdmin(id, why)
      : campaignsApi.rejectAsAdmin(id, why),
  );

  const refresh = () => {
    list.reload();
    counts.reload();
    // The open detail may have just been decided, so do not leave a stale one on screen.
    setDetail(null);
  };

  const campaigns = list.data?.content ?? [];

  /** Applies a decision and reports it only after the backend has confirmed. */
  const confirmDecision = async (reason: string) => {
    if (!decision) return;
    const { campaign, kind } = decision;
    const result = await decide.run(campaign.id, kind, reason || undefined);
    if (!result) return; // The dialog stays open, showing the server's message.
    setNotice(
      kind === 'approve'
        ? `${campaign.title} approved. It now waits on the restaurant.`
        : `${campaign.title} rejected. The creator has been told why.`,
    );
    setDecision(null);
    refresh();
  };

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Campaign governance"
        subtitle="A campaign goes live only after both TryNearby and the restaurant approve."
        action={
          <Button variant="secondary" onClick={refresh}>
            Refresh
          </Button>
        }
      />

      {/* Search and ordering, applied by the database rather than to the page in hand. */}
      <div className="flex flex-wrap items-end gap-space-md">
        <div className="min-w-56 flex-1">
          <Field
            label="Search"
            hint="Matches the campaign code, the creator or the restaurant."
          >
            <input
              className={inputClass}
              type="search"
              value={search}
              placeholder="e.g. Copper, or a campaign code"
              aria-label="Search campaigns"
              onChange={(event) => setSearch(event.target.value)}
            />
          </Field>
        </div>
        <div className="w-48">
          <Field label="Order by">
            <select
              className={inputClass}
              value={sort}
              aria-label="Sort campaigns"
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

      <div role="tablist" aria-label="Filter campaigns" className="ct-scroll-x flex gap-space-xs">
        {FILTERS.map((option) => {
          const selected = filter === option.id;
          const badge = option.id
            ? (counts.data?.content.filter((c) => c.status === option.id).length ?? 0)
            : (counts.data?.totalElements ?? 0);
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
              {option.label} ({badge})
            </button>
          );
        })}
      </div>

      {notice ? <Callout tone="success">{notice}</Callout> : null}
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
          title={emptyTitle(filter, search)}
          hint={emptyHint(filter, search)}
        />
      ) : (
        <ul className="space-y-space-md">
          {campaigns.map((campaign) => {
            const status = toFrontendStatus(campaign.status);
            return (
              <Card as="li" key={campaign.id} className="p-gutter">
                <div className="flex flex-wrap items-start justify-between gap-space-md">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-space-sm">
                      <h3 className="font-headline-sm text-headline-sm text-on-surface">
                        {campaign.title}
                      </h3>
                      <Badge className={STATUS_TONES[status]}>{STATUS_LABELS[status]}</Badge>
                    </div>

                    <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">
                      {campaign.creatorName} · {campaign.restaurantName}
                    </p>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {campaign.offerText} ·{' '}
                      {formatDateRange(campaign.startDate, campaign.endDate)}
                    </p>
                    {/* Which of the two approvals is still outstanding, in plain words. */}
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {approvalSummary(campaign)}
                    </p>

                    {/* Both decisions shown explicitly, not just a summary line, so an approver can
                        see at a glance which of the two is still outstanding. */}
                    <dl className="mt-space-sm flex flex-wrap gap-x-gutter gap-y-space-xs text-body-sm text-body-sm">
                      <div className="flex gap-1">
                        <dt className="text-on-surface-variant">TryNearby</dt>
                        <dd className="text-on-surface">{decisionOf(campaign, 'admin')}</dd>
                      </div>
                      <div className="flex gap-1">
                        <dt className="text-on-surface-variant">Restaurant</dt>
                        <dd className="text-on-surface">{decisionOf(campaign, 'restaurant')}</dd>
                      </div>
                      <div className="flex gap-1">
                        <dt className="text-on-surface-variant">Code</dt>
                        <dd className="font-mono text-on-surface">{campaign.promoCode ?? '—'}</dd>
                      </div>
                      <div className="flex gap-1">
                        <dt className="text-on-surface-variant">Discount</dt>
                        <dd className="text-on-surface">{campaign.discountPercent}%</dd>
                      </div>
                      <div className="flex gap-1">
                        <dt className="text-on-surface-variant">Target</dt>
                        <dd className="text-on-surface">{campaign.targetCustomers} customers</dd>
                      </div>
                      <div className="flex gap-1">
                        <dt className="text-on-surface-variant">Duration</dt>
                        <dd className="text-on-surface">{campaign.durationDays} days</dd>
                      </div>
                      <div className="flex gap-1">
                        <dt className="text-on-surface-variant">Updated</dt>
                        <dd className="text-on-surface">
                          {formatRelative(campaign.submittedAt ?? campaign.createdAt)}
                        </dd>
                      </div>
                    </dl>

                    {campaign.adminReason ? (
                      <p className="mt-space-sm rounded-lg bg-error-container px-space-sm py-space-xs text-body-sm text-body-sm text-on-error-container">
                        {campaign.adminReason}
                      </p>
                    ) : null}
                  </div>

                  {/* The backend decides which decisions are legal for this status. */}
                  {campaign.canApprove ? (
                    <div className="flex shrink-0 flex-wrap gap-space-sm">
                      <Button
                        onClick={() => setDecision({ campaign, kind: 'approve' })}
                        disabled={decide.pending}
                        title="Approve this campaign"
                      >
                        Approve
                      </Button>
                      <Button
                        variant="secondary"
                        onClick={() => setDecision({ campaign, kind: 'reject' })}
                        disabled={decide.pending}
                      >
                        Reject
                      </Button>
                    </div>
                  ) : null}

                  {/* Reading is always allowed. When a decision is still this reviewer's to make,
                      the label says so, so the modal is not mistaken for a read-only summary. */}
                  <Button
                    variant="ghost"
                    className="mt-2"
                    onClick={() => setDetail(campaign)}
                  >
                    {campaign.canApprove ? 'View & approve' : 'View details'}
                  </Button>
                </div>

              </Card>
            );
          })}
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
          loadHistory={campaignsApi.historyForAdmin}
          // Re-read on open so the controls reflect the server's current state, not the list
          // snapshot this row was rendered from.
          loadCampaign={campaignsApi.detailForAdmin}
          party="admin"
          pending={decide.pending}
          // Deciding from the modal opens the same confirmation the row buttons use, so there is
          // one path to a decision rather than two that could drift apart.
          onDecide={(kind) => setDecision({ campaign: detail, kind })}
          onClose={() => setDetail(null)}
        />
      ) : null}
    </div>
  );
}

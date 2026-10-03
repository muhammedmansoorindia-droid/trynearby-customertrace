/**
 * Admin campaign queue.
 *
 * <p>Decisions are sent to the backend and the list is refetched only after the backend confirms,
 * so the screen can never show an approval the server refused. Which actions are offered comes from
 * the backend's own `canApprove` flag rather than a client-side guess.
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
  Field,
  LoadingState,
  SectionHeading,
  inputClass,
} from '@/app/ui';
import { campaignsApi, toFrontendStatus } from '@/services/backend/campaigns';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { cn } from '@/utils';
import { formatDateRange, formatRelative } from '@/utils/format';

const FILTERS = [
  { id: 'PENDING', label: 'Awaiting review' },
  { id: 'ACTIVE', label: 'Live' },
  { id: 'REJECTED', label: 'Rejected' },
  { id: 'DRAFT', label: 'Draft' },
  { id: '', label: 'All' },
] as const;

export function AdminCampaigns() {
  const [filter, setFilter] = useState<string>('PENDING');
  const [rejecting, setRejecting] = useState<number | null>(null);
  const [reason, setReason] = useState('');

  const list = useResource(
    useCallback(
      () => campaignsApi.listForAdmin(filter ? { status: filter } : undefined),
      [filter],
    ),
    [filter],
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
  };

  const campaigns = list.data?.content ?? [];

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
              onClick={() => setFilter(option.id)}
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

      {decide.error ? (
        <Callout tone="error">{decide.error.message}</Callout>
      ) : null}                  {decide.error ? <Callout tone="error">{decide.error.message}</Callout> : null}

      {list.error ? (
        <ErrorState
          message={list.error.message}
          isNetworkError={list.error.isNetworkError}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <LoadingState label="Loading campaigns…" />
      ) : campaigns.length === 0 ? (
        <EmptyState title="No campaigns in this view" hint="Try a different filter above." />
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

                    <dl className="mt-space-sm flex flex-wrap gap-x-gutter gap-y-space-xs text-body-sm text-body-sm">
                      <div className="flex gap-1">
                        <dt className="text-on-surface-variant">Code</dt>
                        <dd className="font-mono text-on-surface">{campaign.promoCode ?? '—'}</dd>
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
                        onClick={async () => {
                          const result = await decide.run(campaign.id, 'approve');
                          if (result) refresh();
                        }}
                        disabled={decide.pending}
                        title="Approve this campaign"
                      >
                        {decide.pending ? 'Approving…' : 'Approve'}
                      </Button>
                      <Button
                        variant="secondary"
                        onClick={() => {
                          setRejecting(rejecting === campaign.id ? null : campaign.id);
                          setReason('');
                        }}
                      >
                        Reject
                      </Button>
                    </div>
                  ) : null}
                </div>

                {rejecting === campaign.id ? (
                  <form
                    className="mt-space-md space-y-space-sm border-t border-outline-variant/30 pt-space-md"
                    onSubmit={async (event) => {
                      event.preventDefault();
                      const result = await decide.run(campaign.id, 'reject', reason.trim());
                      if (result) {
                        setRejecting(null);
                        setReason('');
                        refresh();
                      }
                    }}
                  >
                    <Field
                      label="Reason for rejection"
                      hint="The creator sees this verbatim, so be specific."
                    >
                      <textarea
                        className={cn(inputClass, 'min-h-20 resize-y')}
                        value={reason}
                        onChange={(event) => setReason(event.target.value)}
                        placeholder="e.g. The offer cannot be verified at settlement."
                      />
                    </Field>
                    <div className="flex gap-space-sm">
                      <Button type="submit" variant="danger" disabled={decide.pending}>
                        Confirm rejection
                      </Button>
                      <Button variant="ghost" onClick={() => setRejecting(null)}>
                        Cancel
                      </Button>
                    </div>
                  </form>
                ) : null}
              </Card>
            );
          })}
        </ul>
      )}
    </div>
  );
}

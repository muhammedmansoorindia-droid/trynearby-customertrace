/**
 * Admin campaign queue.
 *
 * Approve/reject is driven entirely by `campaignWorkflow.checkTransition` — the buttons are
 * rendered from `canPerform`, so an illegal action is never offered in the first place
 * rather than failing after a click.
 */

import { useMemo, useState } from 'react';

import { useAppStore } from '@/app/store';
import { Badge, Button, Card, EmptyState, Field, SectionHeading, inputClass } from '@/app/ui';
import { STATUS_LABELS, STATUS_TONES, canPerform } from '@/services/campaignWorkflow';
import type { CampaignStatus } from '@/types/models';
import { cn } from '@/utils';
import { formatDateRange, formatRelative } from '@/utils/format';

const FILTERS: Array<{ id: CampaignStatus | 'all'; label: string }> = [
  { id: 'pending_admin', label: 'Awaiting review' },
  { id: 'live', label: 'Live' },
  { id: 'approved', label: 'Approved' },
  { id: 'rejected', label: 'Rejected' },
  { id: 'draft', label: 'Draft' },
  { id: 'all', label: 'All' },
];

export function AdminCampaigns() {
  const db = useAppStore((s) => s.db);
  const performAction = useAppStore((s) => s.performAction);

  const [filter, setFilter] = useState<CampaignStatus | 'all'>('pending_admin');
  const [rejecting, setRejecting] = useState<string | null>(null);
  const [reason, setReason] = useState('');

  const campaigns = useMemo(
    () => (filter === 'all' ? db.campaigns : db.campaigns.filter((c) => c.status === filter)),
    [db.campaigns, filter],
  );

  const counts = useMemo(() => {
    const map = new Map<CampaignStatus | 'all', number>();
    FILTERS.forEach((f) => {
      map.set(
        f.id,
        f.id === 'all'
          ? db.campaigns.length
          : db.campaigns.filter((c) => c.status === f.id).length,
      );
    });
    return map;
  }, [db.campaigns]);

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Campaign governance"
        subtitle="Approving here immediately updates the creator's portal — one store, no sync."
      />

      <div role="tablist" aria-label="Filter campaigns" className="ct-scroll-x flex gap-space-xs">
        {FILTERS.map((option) => (
          <button
            key={option.id}
            role="tab"
            aria-selected={filter === option.id}
            type="button"
            onClick={() => setFilter(option.id)}
            className={cn(
              'whitespace-nowrap rounded-full border px-space-md py-space-sm font-label-sm text-label-sm transition-colors',
              filter === option.id
                ? 'bg-primary text-on-primary border-primary'
                : 'bg-surface-container-lowest text-on-surface-variant border-outline-variant/40 hover:bg-surface-container-high',
            )}
          >
            {option.label} ({counts.get(option.id) ?? 0})
          </button>
        ))}
      </div>

      {campaigns.length === 0 ? (
        <EmptyState title="No campaigns in this view" hint="Try a different filter above." />
      ) : (
        <ul className="space-y-space-md">
          {campaigns.map((campaign) => {
            const creator = db.creators.find((c) => c.id === campaign.creatorId);
            const restaurant = db.restaurants.find((r) => r.id === campaign.restaurantId);
            const canApprove = canPerform(campaign.status, 'approve');
            const canReject = canPerform(campaign.status, 'reject');

            return (
              <Card as="li" key={campaign.id} className="p-gutter">
                <div className="flex flex-wrap items-start justify-between gap-space-md">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-space-sm">
                      <h3 className="font-headline-sm text-headline-sm text-on-surface">
                        {campaign.title}
                      </h3>
                      <Badge className={STATUS_TONES[campaign.status]}>
                        {STATUS_LABELS[campaign.status]}
                      </Badge>
                    </div>

                    <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">
                      {creator?.displayName} · {restaurant?.name}, {restaurant?.city}
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
                          {formatRelative(campaign.decidedAt ?? campaign.createdAt)}
                        </dd>
                      </div>
                    </dl>

                    {campaign.rejectionReason ? (
                      <p className="mt-space-sm rounded-lg bg-error-container px-space-sm py-space-xs text-body-sm text-body-sm text-on-error-container">
                        {campaign.rejectionReason}
                      </p>
                    ) : null}
                  </div>

                  {canApprove || canReject ? (
                    <div className="flex shrink-0 flex-wrap gap-space-sm">
                      {canApprove ? (
                        <Button
                          onClick={() => performAction(campaign.id, 'approve')}
                          title="Approve this campaign"
                        >
                          Approve
                        </Button>
                      ) : null}
                      {canReject ? (
                        <Button
                          variant="secondary"
                          onClick={() => {
                            setRejecting(rejecting === campaign.id ? null : campaign.id);
                            setReason('');
                          }}
                        >
                          Reject
                        </Button>
                      ) : null}
                    </div>
                  ) : null}
                </div>

                {rejecting === campaign.id ? (
                  <form
                    className="mt-space-md space-y-space-sm border-t border-outline-variant/30 pt-space-md"
                    onSubmit={(event) => {
                      event.preventDefault();
                      if (performAction(campaign.id, 'reject', reason)) {
                        setRejecting(null);
                        setReason('');
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
                      <Button type="submit" variant="danger">
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

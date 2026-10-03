/**
 * The creator's campaign list, with the shareable QR pass.
 *
 * <p>The QR encodes the promo code as plain text — decoding it must never require a network
 * round-trip, because the pass has to work in a restaurant with no data connection. That is
 * also why a typed-code fallback exists on the restaurant's scanner.
 *
 * <p>Submission is a backend call: the promo code is minted server-side, so the code shown here is
 * always the one the restaurant's scanner will accept. Whether a submit is offered comes from the
 * campaign's own `canSubmit` flag.
 */

import { useCallback, useEffect, useState } from 'react';

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
import { campaignsApi, toFrontendStatus } from '@/services/backend/campaigns';
import { reportsApi } from '@/services/backend/reports';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { formatDateRange, formatRelative } from '@/utils/format';

export function CreatorCampaigns() {
  const list = useResource(useCallback(() => campaignsApi.listForCreator(), []), []);
  const performance = useResource(useCallback(() => reportsApi.creatorCampaigns(), []), []);

  const submit = useMutation(async (id: number) => campaignsApi.submit(id));

  const refresh = () => {
    list.reload();
    performance.reload();
  };

  const campaigns = list.data?.content ?? [];
  const byId = new Map((performance.data ?? []).map((row) => [row.id, row]));

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Your campaigns"
        subtitle="Each live campaign has a QR pass. Share it with your audience — the restaurant validates it at the till."
        action={
          <Button variant="secondary" onClick={refresh}>
            Refresh
          </Button>
        }
      />

      {submit.error ? <Callout tone="error">{submit.error.message}</Callout> : null}

      {list.error ? (
        <ErrorState
          message={list.error.message}
          isNetworkError={list.error.isNetworkError}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <LoadingState label="Loading your campaigns…" />
      ) : campaigns.length === 0 ? (
        <EmptyState title="No campaigns yet" hint="Create one to get a QR pass to share." />
      ) : (
        <ul className="grid gap-gutter lg:grid-cols-2">
          {campaigns.map((campaign) => {
            const status = toFrontendStatus(campaign.status);
            const metrics = byId.get(campaign.id);
            const quotaPercent =
              metrics && campaign.targetCustomers > 0
                ? Math.round((metrics.verifiedCustomers / campaign.targetCustomers) * 100)
                : 0;

            return (
              <Card as="li" key={campaign.id} className="p-gutter space-y-space-md">
                <div className="flex flex-wrap items-start justify-between gap-space-sm">
                  <div>
                    <h3 className="font-headline-sm text-headline-sm text-on-surface">
                      {campaign.title}
                    </h3>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {campaign.restaurantName}
                    </p>
                  </div>
                  <Badge className={STATUS_TONES[status]}>{STATUS_LABELS[status]}</Badge>
                </div>

                <p className="text-body-md text-body-md text-on-surface">{campaign.offerText}</p>
                <p className="text-body-sm text-body-sm text-on-surface-variant">
                  {formatDateRange(campaign.startDate, campaign.endDate)}
                  {campaign.submittedAt
                    ? ` · submitted ${formatRelative(campaign.submittedAt)}`
                    : ''}
                </p>

                {campaign.status === 'REJECTED' ? (
                  <p className="rounded-lg bg-error-container px-space-sm py-space-xs text-body-sm text-body-sm text-on-error-container">
                    <strong className="font-label-md block">Rejected</strong>
                    {campaign.adminReason ?? campaign.restaurantReason ?? 'No reason supplied.'}
                  </p>
                ) : null}

                {/* Says plainly whose approval is still outstanding. */}
                {campaign.status === 'PENDING' ? (
                  <Callout tone="info">
                    {campaign.adminDecision === 'APPROVED'
                      ? 'TryNearby has approved. Waiting for the restaurant before it goes live.'
                      : campaign.restaurantDecision === 'APPROVED'
                        ? 'The restaurant has approved. Waiting for TryNearby before it goes live.'
                        : 'Submitted. Both TryNearby and the restaurant must approve before it goes live.'}
                  </Callout>
                ) : null}

                {campaign.promoCode ? (
                  <div className="flex flex-wrap items-center gap-gutter rounded-lg bg-surface-container p-space-md">
                    <QrPass value={campaign.promoCode} />
                    <div className="min-w-0">
                      <p className="font-label-sm text-label-sm uppercase tracking-wide text-on-surface-variant">
                        Promo code
                      </p>
                      <p className="font-mono text-headline-md text-headline-md text-on-surface">
                        {campaign.promoCode}
                      </p>
                      <dl className="mt-1 text-body-sm text-body-sm text-on-surface-variant">
                        <div className="flex gap-1">
                          <dt>Target</dt>
                          <dd className="text-on-surface">{campaign.targetCustomers} customers</dd>
                        </div>
                        <div className="flex gap-1">
                          <dt>Verified</dt>
                          <dd className="text-on-surface">
                            {metrics?.verifiedCustomers ?? 0} ({quotaPercent}%)
                          </dd>
                        </div>
                      </dl>
                    </div>
                  </div>
                ) : (
                  <p className="text-body-sm text-body-sm text-on-surface-variant">
                    A code is minted when this campaign is submitted for review.
                  </p>
                )}

                {/* The backend decides whether this creator may submit right now. */}
                {campaign.canSubmit ? (
                  <Button
                    disabled={submit.pending}
                    onClick={async () => {
                      const result = await submit.run(campaign.id);
                      if (result) refresh();
                    }}
                  >
                    {submit.pending
                      ? 'Submitting…'
                      : campaign.status === 'REJECTED'
                        ? 'Revise & resubmit'
                        : 'Submit for review'}
                  </Button>
                ) : null}
              </Card>
            );
          })}
        </ul>
      )}
    </div>
  );
}
/**
 * Renders the code as a QR image.
 *
 * Generated lazily and locally via `qrcode`; if generation fails the code is still shown as
 * text beside it, so a missing QR can never block a creator from sharing a campaign.
 */
function QrPass({ value }: { value: string }) {
  const [src, setSrc] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void import('qrcode')
      .then((mod) => mod.default.toDataURL(value, { margin: 1, width: 132 }))
      .then((url) => {
        if (!cancelled) setSrc(url);
      })
      .catch(() => {
        if (!cancelled) setSrc(null);
      });
    return () => {
      cancelled = true;
    };
  }, [value]);

  if (!src) {
    return (
      <div className="h-[132px] w-[132px] shrink-0 rounded-lg border border-outline-variant/30 bg-surface-container-lowest" />
    );
  }

  return (
    <img
      src={src}
      alt={`QR code for promo code ${value}`}
      className="h-[132px] w-[132px] shrink-0 rounded-lg border border-outline-variant/30 bg-white p-1"
    />
  );
}

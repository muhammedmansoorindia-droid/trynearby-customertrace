/**
 * The creator's campaign list, with the shareable QR pass.
 *
 * The QR encodes the promo code as plain text — decoding it must never require a network
 * round-trip, because the pass has to work in a restaurant with no data connection. That is
 * also why a typed-code fallback exists on the restaurant's scanner.
 */

import { useEffect, useState } from 'react';

import { NoData } from '@/app/PortalLayout';
import { useAppStore } from '@/app/store';
import { Badge, Button, Card, EmptyState, SectionHeading } from '@/app/ui';
import { STATUS_LABELS, STATUS_TONES, canPerform } from '@/services/campaignWorkflow';
import { selectCampaignMetrics } from '@/services/metrics';
import { formatDateRange, formatRelative } from '@/utils/format';

export function CreatorCampaigns() {
  const db = useAppStore((s) => s.db);
  const creatorId = useAppStore((s) => s.session.creatorId);
  const performAction = useAppStore((s) => s.performAction);

  const creator = db.creators.find((c) => c.id === creatorId);
  if (!creator) {
    return <NoData message="Switch portal and pick a creator to continue." />;
  }

  const campaigns = db.campaigns.filter((c) => c.creatorId === creator.id);

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Your campaigns"
        subtitle="Each live campaign has a QR pass. Share it with your audience — the restaurant validates it at the till."
      />

      {campaigns.length === 0 ? (
        <EmptyState title="No campaigns yet" hint="Create one to get a QR pass to share." />
      ) : (
        <ul className="grid gap-gutter lg:grid-cols-2">
          {campaigns.map((campaign) => {
            const restaurant = db.restaurants.find((r) => r.id === campaign.restaurantId);
            const m = selectCampaignMetrics(campaign, db.scans, db.redemptions);

            return (
              <Card as="li" key={campaign.id} className="p-gutter space-y-space-md">
                <div className="flex flex-wrap items-start justify-between gap-space-sm">
                  <div>
                    <h3 className="font-headline-sm text-headline-sm text-on-surface">
                      {campaign.title}
                    </h3>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {restaurant?.name} · {restaurant?.city}
                    </p>
                  </div>
                  <Badge className={STATUS_TONES[campaign.status]}>
                    {STATUS_LABELS[campaign.status]}
                  </Badge>
                </div>

                <p className="text-body-md text-body-md text-on-surface">{campaign.offerText}</p>
                <p className="text-body-sm text-body-sm text-on-surface-variant">
                  {formatDateRange(campaign.startDate, campaign.endDate)}
                  {campaign.submittedAt
                    ? ` · submitted ${formatRelative(campaign.submittedAt)}`
                    : ''}
                </p>

                {campaign.rejectionReason ? (
                  <p className="rounded-lg bg-error-container px-space-sm py-space-xs text-body-sm text-body-sm text-on-error-container">
                    <strong className="font-label-md block">Rejected</strong>
                    {campaign.rejectionReason}
                  </p>
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
                            {m.verifiedCustomers} ({m.quotaUsedPercent}%)
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

                <div className="flex flex-wrap gap-space-sm">
                  {canPerform(campaign.status, 'submit') ? (
                    <Button onClick={() => performAction(campaign.id, 'submit')}>
                      {campaign.status === 'rejected' ? 'Revise & resubmit' : 'Submit for review'}
                    </Button>
                  ) : null}
                  {canPerform(campaign.status, 'go_live') ? (
                    <Button onClick={() => performAction(campaign.id, 'go_live')}>Go live</Button>
                  ) : null}
                </div>
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

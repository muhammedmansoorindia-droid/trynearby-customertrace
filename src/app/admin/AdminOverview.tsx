/**
 * Admin overview — platform health and the approval queue.
 *
 * The two headline numbers are deliberately *not* the same: scans and verified customers are
 * different populations, and showing one blended "engagement" figure is the mistake this
 * product exists to prevent.
 */

import { Link } from 'react-router-dom';

import { useAppStore } from '@/app/store';
import { Badge, Card, FunnelBar, SectionHeading, StatTile } from '@/app/ui';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { formatCompactINR, formatDateRange, formatRelative } from '@/utils/format';

export function AdminOverview() {
  const db = useAppStore((s) => s.db);

  const scans = db.scans.filter((s) => s.result === 'valid');
  const redemptions = db.redemptions;
  const verified = new Set(redemptions.map((r, i) => r.customerRef ?? `anon:${r.id || i}`));
  const discount = redemptions.reduce((sum, r) => sum + r.discountAmountInPaise, 0);
  const revenue = redemptions.reduce((sum, r) => sum + r.billAmountInPaise, 0);

  const queue = db.campaigns.filter((c) => c.status === 'pending_admin');
  const live = db.campaigns.filter((c) => c.status === 'live');
  const pausedPartners = db.restaurants.filter((r) => r.status !== 'active');

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Platform overview"
        subtitle="Every number below is derived from the same event ledger the portals write to."
        action={
          <Link
            to="/admin/campaigns"
            className="font-label-md text-label-md text-primary hover:underline"
          >
            Review queue ({queue.length})
          </Link>
        }
      />

      <div className="grid gap-space-md sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="QR & code scans"
          value={scans.length.toLocaleString('en-IN')}
          hint="Interest only — never counted as a customer"
          tone="muted"
        />
        <StatTile
          label="Verified customers"
          value={verified.size.toLocaleString('en-IN')}
          hint="Distinct diners, derived from redemptions"
          tone="primary"
        />
        <StatTile
          label="Redemptions"
          value={redemptions.length.toLocaleString('en-IN')}
          hint="Settled at the till"
        />
        <StatTile
          label="Discount provided"
          value={formatCompactINR(discount)}
          hint={`${formatCompactINR(revenue)} revenue influenced`}
        />
      </div>

      <Card className="p-gutter">
        <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
          Attention funnel — all live campaigns
        </h3>
        <FunnelBar
          scans={scans.length}
          redemptions={redemptions.length}
          customers={verified.size}
        />
        <p className="text-body-sm text-body-sm text-on-surface-variant mt-space-md">
          {scans.length - redemptions.length} scans never converted and{' '}
          {redemptions.length - verified.size} redemptions were repeat visits. Both gaps are
          real and are the reason the numbers do not match.
        </p>
      </Card>

      <div className="grid gap-gutter lg:grid-cols-2">
        <Card className="p-gutter">
          <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
            Awaiting review
          </h3>
          {queue.length === 0 ? (
            <p className="text-body-sm text-body-sm text-on-surface-variant">
              The queue is clear.
            </p>
          ) : (
            <ul className="space-y-space-sm">
              {queue.map((campaign) => {
                const creator = db.creators.find((c) => c.id === campaign.creatorId);
                const restaurant = db.restaurants.find((r) => r.id === campaign.restaurantId);
                return (
                  <li key={campaign.id} className="flex items-start justify-between gap-space-sm">
                    <div>
                      <p className="font-label-md text-label-md text-on-surface">
                        {campaign.title}
                      </p>
                      <p className="text-body-sm text-body-sm text-on-surface-variant">
                        {creator?.displayName} → {restaurant?.name}
                      </p>
                    </div>
                    <Badge className={STATUS_TONES[campaign.status]}>
                      {formatRelative(campaign.submittedAt ?? campaign.createdAt)}
                    </Badge>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>

        <Card className="p-gutter">
          <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
            Live campaigns
          </h3>
          <ul className="space-y-space-sm">
            {live.map((campaign) => (
              <li key={campaign.id} className="flex items-start justify-between gap-space-sm">
                <div>
                  <p className="font-label-md text-label-md text-on-surface">
                    {campaign.title}
                  </p>
                  <p className="text-body-sm text-body-sm text-on-surface-variant">
                    {formatDateRange(campaign.startDate, campaign.endDate)} · target{' '}
                    {campaign.targetCustomers}
                  </p>
                </div>
                <Badge className={STATUS_TONES[campaign.status]}>
                  {STATUS_LABELS[campaign.status]}
                </Badge>
              </li>
            ))}
          </ul>
          {pausedPartners.length > 0 ? (
            <p className="text-body-sm text-body-sm text-on-surface-variant mt-space-md">
              {pausedPartners.length} partner
              {pausedPartners.length === 1 ? ' is' : 's are'} not currently active:{' '}
              {pausedPartners.map((r) => r.name).join(', ')}.
            </p>
          ) : null}
        </Card>
      </div>
    </div>
  );
}

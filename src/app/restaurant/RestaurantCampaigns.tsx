/**
 * Campaigns running at the restaurant's venue.
 *
 * A read-only mirror of the creator's view — deliberately, because a venue does not get to
 * edit another creator's campaign. The redemption counter is shown next to the target so
 * staff can see the cap being consumed in real time.
 */

import { useAppStore } from '@/app/store';
import { NoData } from '@/app/PortalLayout';
import { Badge, Card, EmptyState, SectionHeading } from '@/app/ui';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { selectCampaignMetrics } from '@/services/metrics';
import { formatCompactINR, formatDateRange } from '@/utils/format';

export function RestaurantCampaigns() {
  const db = useAppStore((s) => s.db);
  const restaurantId = useAppStore((s) => s.session.restaurantId);

  const restaurant = db.restaurants.find((r) => r.id === restaurantId);
  if (!restaurant) {
    return <NoData message="Switch portal and pick a restaurant to continue." />;
  }

  const campaigns = db.campaigns.filter((c) => c.restaurantId === restaurant.id);

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Campaigns at your venue"
        subtitle="Offers creators have run here, with the quota consumed so far."
      />

      {campaigns.length === 0 ? (
        <EmptyState
          title="No campaigns yet"
          hint="When a creator submits a campaign for your venue it will appear here."
        />
      ) : (
        <ul className="grid gap-space-md md:grid-cols-2">
          {campaigns.map((campaign) => {
            const creator = db.creators.find((c) => c.id === campaign.creatorId);
            const m = selectCampaignMetrics(campaign, db.scans, db.redemptions);
            const used = Math.min(100, m.quotaUsedPercent);

            return (
              <Card as="li" key={campaign.id} className="p-gutter">
                <div className="flex flex-wrap items-start justify-between gap-space-sm">
                  <div>
                    <h3 className="font-headline-sm text-headline-sm text-on-surface">
                      {campaign.title}
                    </h3>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      by {creator?.displayName} · @{creator?.handle}
                    </p>
                  </div>
                  <Badge className={STATUS_TONES[campaign.status]}>
                    {STATUS_LABELS[campaign.status]}
                  </Badge>
                </div>

                <p className="text-body-md text-body-md text-on-surface mt-space-sm">
                  {campaign.offerText}
                </p>
                <p className="text-body-sm text-body-sm text-on-surface-variant">
                  {formatDateRange(campaign.startDate, campaign.endDate)} ·{' '}
                  <span className="font-mono">{campaign.promoCode}</span>
                </p>

                <div className="mt-space-md">
                  <div className="flex items-center justify-between text-body-sm text-body-sm">
                    <span className="text-on-surface-variant">Quota consumed</span>
                    <span className="text-on-surface">
                      {m.redemptionCount} / {campaign.targetCustomers}
                    </span>
                  </div>
                  <div className="mt-1 h-2 rounded-full bg-surface-container overflow-hidden">
                    <div className="h-full rounded-full bg-primary" style={{ width: `${used}%` }} />
                  </div>
                </div>

                <dl className="mt-space-md grid grid-cols-3 gap-space-sm text-body-sm text-body-sm">
                  <div>
                    <dt className="text-on-surface-variant">Scans</dt>
                    <dd className="text-on-surface">{m.totalScans}</dd>
                  </div>
                  <div>
                    <dt className="text-on-surface-variant">Customers</dt>
                    <dd className="text-on-surface">{m.verifiedCustomers}</dd>
                  </div>
                  <div>
                    <dt className="text-on-surface-variant">Discount</dt>
                    <dd className="text-on-surface">
                      {formatCompactINR(m.discountProvidedInPaise)}
                    </dd>
                  </div>
                </dl>
              </Card>
            );
          })}
        </ul>
      )}
    </div>
  );
}

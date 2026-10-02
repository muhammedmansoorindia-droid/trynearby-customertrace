/**
 * Creator dashboard.
 *
 * The funnel is shown per campaign, not as one lifetime total, because the honest question a
 * creator asks is "which of my campaigns actually drove visits?" — a blended number would
 * let a dead campaign hide behind a successful one.
 */

import { Link } from 'react-router-dom';

import { NoData } from '@/app/PortalLayout';
import { useAppStore } from '@/app/store';
import { Badge, Button, Card, EmptyState, FunnelBar, SectionHeading, StatTile } from '@/app/ui';
import { selectCampaignMetrics, selectCreatorMetrics } from '@/services/metrics';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { formatCompactINR } from '@/utils/format';

export function CreatorDashboard() {
  const db = useAppStore((s) => s.db);
  const creatorId = useAppStore((s) => s.session.creatorId);

  const creator = db.creators.find((c) => c.id === creatorId);
  if (!creator) {
    return <NoData message="Switch portal and pick a creator to continue." />;
  }

  const metrics = selectCreatorMetrics(creator.id, db);
  const campaigns = db.campaigns.filter((c) => c.creatorId === creator.id);
  const active = campaigns.filter((c) => c.status === 'live' || c.status === 'approved');
  const awaiting = campaigns.filter((c) => c.status === 'pending_admin');

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title={creator.displayName}
        subtitle={`@${creator.handle} · ${creator.followers.toLocaleString('en-IN')} followers · ${creator.niche.join(' · ')}`}
        action={
          <Link to="/creator/new">
            <Button>New campaign</Button>
          </Link>
        }
      />

      <div className="grid gap-space-md sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="Verified customers"
          value={metrics.verifiedCustomers.toLocaleString('en-IN')}
          hint="Distinct diners who actually redeemed"
          tone="primary"
        />
        <StatTile
          label="Passes scanned"
          value={metrics.totalScans.toLocaleString('en-IN')}
          hint="Reach — not customers"
          tone="muted"
        />
        <StatTile
          label="Discount provided"
          value={formatCompactINR(metrics.discountProvidedInPaise)}
          hint={`${metrics.redemptions} redemptions`}
        />
        <StatTile
          label="Live campaigns"
          value={String(metrics.activeCampaignCount)}
          hint={`${metrics.campaignCount} total · ${awaiting.length} awaiting review`}
        />
      </div>

      {active.length === 0 ? (
        <EmptyState
          title="No live campaigns"
          hint="Submit a campaign and it will appear in the admin review queue."
        />
      ) : (
        <div className="space-y-gutter">
          {active.map((campaign) => {
            const restaurant = db.restaurants.find((r) => r.id === campaign.restaurantId);
            const m = selectCampaignMetrics(campaign, db.scans, db.redemptions);

            return (
              <Card key={campaign.id} className="p-gutter">
                <div className="flex flex-wrap items-start justify-between gap-space-md">
                  <div>
                    <div className="flex flex-wrap items-center gap-space-sm">
                      <h3 className="font-headline-sm text-headline-sm text-on-surface">
                        {campaign.title}
                      </h3>
                      <Badge className={STATUS_TONES[campaign.status]}>
                        {STATUS_LABELS[campaign.status]}
                      </Badge>
                    </div>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {restaurant?.name} · {campaign.offerText}
                    </p>
                  </div>
                  <Link
                    to="/creator/campaigns"
                    className="font-label-md text-label-md text-primary hover:underline"
                  >
                    View pass
                  </Link>
                </div>

                <div className="mt-space-md grid gap-gutter lg:grid-cols-2">
                  <FunnelBar
                    scans={m.totalScans}
                    redemptions={m.redemptionCount}
                    customers={m.verifiedCustomers}
                  />
                  <dl className="grid grid-cols-2 gap-space-sm self-center text-body-sm text-body-sm">
                    <div>
                      <dt className="text-on-surface-variant">Target reached</dt>
                      <dd className="text-on-surface">
                        {m.verifiedCustomers} / {campaign.targetCustomers}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-on-surface-variant">Discount given</dt>
                      <dd className="text-on-surface">
                        {formatCompactINR(m.discountProvidedInPaise)}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-on-surface-variant">Scan → redeem</dt>
                      <dd className="text-on-surface">
                        {Math.round(m.scanToRedemptionRate * 100)}%
                      </dd>
                    </div>
                    <div>
                      <dt className="text-on-surface-variant">Revenue influenced</dt>
                      <dd className="text-on-surface">
                        {formatCompactINR(m.revenueInfluencedInPaise)}
                      </dd>
                    </div>
                  </dl>
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}

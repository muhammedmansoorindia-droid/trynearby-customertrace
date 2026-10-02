/**
 * Restaurant dashboard.
 *
 * Scoped to the restaurant chosen at the role screen. If that id no longer resolves (data
 * reset in another tab, say) the screen says so instead of rendering zeros, which would
 * read as "no customers ever came in".
 */

import { Link } from 'react-router-dom';

import { useAppStore } from '@/app/store';
import { NoData } from '@/app/PortalLayout';
import { Badge, Button, Card, FunnelBar, SectionHeading, StatTile } from '@/app/ui';
import { selectRestaurantMetrics } from '@/services/metrics';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { formatCompactINR, formatDateRange, formatRelative } from '@/utils/format';

export function RestaurantDashboard() {
  const db = useAppStore((s) => s.db);
  const restaurantId = useAppStore((s) => s.session.restaurantId);

  const restaurant = db.restaurants.find((r) => r.id === restaurantId);
  if (!restaurant) {
    return <NoData message="Switch portal and pick a restaurant to continue." />;
  }

  const metrics = selectRestaurantMetrics(restaurant.id, db);
  const subscription = db.subscriptions.find((s) => s.restaurantId === restaurant.id);
  const campaigns = db.campaigns.filter((c) => c.restaurantId === restaurant.id);
  const openBookings = db.bookings.filter(
    (b) => b.restaurantId === restaurant.id && b.status === 'requested',
  );

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title={restaurant.name}
        subtitle={`${restaurant.cuisine.join(' · ')} · ${restaurant.city}, ${restaurant.region}`}
        action={
          <Link to="/restaurant/scan">
            <Button>Scan &amp; redeem</Button>
          </Link>
        }
      />

      <div className="grid gap-space-md sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="Scans at your venue"
          value={metrics.totalScans.toLocaleString('en-IN')}
          hint="Passes shown — interest, not sales"
          tone="muted"
        />
        <StatTile
          label="Verified customers"
          value={metrics.verifiedCustomers.toLocaleString('en-IN')}
          hint="Distinct diners who redeemed"
          tone="primary"
        />
        <StatTile
          label="Discount given"
          value={formatCompactINR(metrics.discountProvidedInPaise)}
          hint={`${metrics.redemptions} redemptions settled`}
        />
        <StatTile
          label="Rating"
          value={metrics.reviewCount ? metrics.averageRating.toFixed(1) : '—'}
          hint={`${metrics.reviewCount} review${metrics.reviewCount === 1 ? '' : 's'}`}
        />
      </div>

      <div className="grid gap-gutter lg:grid-cols-3">
        <Card className="p-gutter lg:col-span-2">
          <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
            How your traffic actually converts
          </h3>
          <FunnelBar
            scans={metrics.totalScans}
            redemptions={metrics.redemptions}
            customers={metrics.verifiedCustomers}
          />
          <p className="text-body-sm text-body-sm text-on-surface-variant mt-space-md">
            A pass being scanned proves someone saw the offer. Only a redemption at the till
            proves they spent money — so this gap is your real conversion rate.
          </p>
        </Card>


        <Card className="p-gutter">
          <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
            Plan
          </h3>
          {subscription ? (
            <dl className="space-y-space-sm text-body-md text-body-md">
              <div className="flex justify-between">
                <dt className="text-on-surface-variant">Tier</dt>
                <dd className="capitalize text-on-surface">{subscription.tier}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-on-surface-variant">Status</dt>
                <dd className="text-on-surface capitalize">
                  {subscription.status.replace('_', ' ')}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-on-surface-variant">Billing</dt>
                <dd className="text-on-surface capitalize">{subscription.billingCycle}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-on-surface-variant">Renews</dt>
                <dd className="text-on-surface">{formatRelative(subscription.renewsAt)}</dd>
              </div>
            </dl>
          ) : (
            <p className="text-body-sm text-body-sm text-on-surface-variant">No active plan.</p>
          )}

          {openBookings.length > 0 ? (
            <Link
              to="/restaurant/bookings"
              className="mt-space-md inline-flex items-center gap-space-xs font-label-md text-label-md text-primary hover:underline"
            >
              {openBookings.length} booking{openBookings.length === 1 ? '' : 's'} awaiting reply
              <span className="material-symbols-outlined text-[18px]" aria-hidden="true">
                arrow_forward
              </span>
            </Link>
          ) : null}
        </Card>
      </div>

      <Card className="p-gutter">
        <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
          Campaigns at your venue
        </h3>
        {campaigns.length === 0 ? (
          <p className="text-body-sm text-body-sm text-on-surface-variant">
            No creators have run a campaign here yet.
          </p>
        ) : (
          <ul className="divide-y divide-outline-variant/20">
            {campaigns.map((campaign) => (
              <li
                key={campaign.id}
                className="flex flex-wrap items-center justify-between gap-space-sm py-space-sm"
              >
                <div>
                  <p className="font-label-md text-label-md text-on-surface">{campaign.title}</p>
                  <p className="text-body-sm text-body-sm text-on-surface-variant">
                    {campaign.offerText} ·{' '}
                    {formatDateRange(campaign.startDate, campaign.endDate)}
                  </p>
                </div>
                <div className="flex items-center gap-space-sm">
                  <span className="font-mono text-body-sm text-body-sm text-on-surface-variant">
                    {campaign.promoCode}
                  </span>
                  <Badge className={STATUS_TONES[campaign.status]}>
                    {STATUS_LABELS[campaign.status]}
                  </Badge>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

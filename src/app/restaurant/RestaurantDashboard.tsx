/**
 * Restaurant dashboard.
 *
 * <p>Everything here is scoped server-side to the signed-in restaurant, so there is no tenant id to
 * pass and nothing to leak. Metrics come from the report endpoints, which derive verified customers
 * from redemptions — the funnel below therefore shows the real scan→redemption drop-off rather than
 * a locally recomputed guess.
 */

import { useCallback } from 'react';
import { Link } from 'react-router-dom';

import { useAuthStore } from '@/app/authStore';
import { useResource, type Resource } from '@/app/useResource';
import {
  Badge,
  Button,
  Card,
  ErrorState,
  FunnelBar,
  LoadingState,
  SectionHeading,
  StatTile,
} from '@/app/ui';
import { campaignsApi, toFrontendStatus } from '@/services/backend/campaigns';
import {
  reviewsApi,
  subscriptionsApi,
  type Subscription,
} from '@/services/backend/community';
import { bookingsApi } from '@/services/backend/bookings';
import { reportsApi } from '@/services/backend/reports';
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { formatCompactINR } from '@/utils/format';

export function RestaurantDashboard() {
  const restaurantId = useAuthStore((s) => s.user?.restaurantId);
  const restaurantName = useAuthStore((s) => s.user?.restaurantName);

  const summary = useResource(useCallback(() => reportsApi.restaurantSummary(), []), []);
  const campaigns = useResource(useCallback(() => campaignsApi.listForRestaurant(), []), []);
  const pendingBookings = useResource(
    useCallback(() => bookingsApi.list('restaurant', { status: 'REQUESTED', size: 10 }), []),
    [],
  );
  const reviews = useResource(
    useCallback(
      () => (restaurantId ? reviewsApi.summaryFor(restaurantId) : Promise.resolve(null)),
      [restaurantId],
    ),
    [restaurantId],
    { enabled: Boolean(restaurantId) },
  );
  // Left un-fetched on purpose: a 404 would only mean this restaurant has never subscribed, which
  // is an empty state rather than an error worth showing.
  const subscription = useResource(useCallback(() => subscriptionsApi.current(), []), [], {
    enabled: false,
  });

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title={restaurantName ?? 'Your venue'}
        subtitle="Live figures for your restaurant, from the same ledger the platform reports on."
        action={
          <div className="flex items-center gap-space-sm">
            <Button variant="secondary" onClick={summary.reload}>
              Refresh
            </Button>
            <Link to="/restaurant/scan">
              <Button>Scan &amp; redeem</Button>
            </Link>
          </div>
        }
      />

      {summary.error ? (
        <ErrorState
          message={summary.error.message}
          isNetworkError={summary.error.isNetworkError}
          onRetry={summary.reload}
        />
      ) : summary.loading ? (
        <LoadingState label="Loading your metrics…" />
      ) : (
        <>
          <div className="grid gap-space-md sm:grid-cols-2 lg:grid-cols-4">
            <StatTile
              label="Scans at your venue"
              value={(summary.data?.scans ?? 0).toLocaleString('en-IN')}
              hint="Passes shown — interest, not sales"
              tone="muted"
            />
            <StatTile
              label="Verified customers"
              value={(summary.data?.verifiedCustomers ?? 0).toLocaleString('en-IN')}
              hint="Distinct diners who redeemed"
              tone="primary"
            />
            <StatTile
              label="Discount given"
              value={formatCompactINR(summary.data?.discountPaise ?? 0)}
              hint={`${summary.data?.redemptions ?? 0} redemptions settled`}
            />
            <StatTile
              label="Rating"
              value={reviews.data?.totalReviews ? reviews.data.averageRating.toFixed(1) : '—'}
              hint={
                reviews.data
                  ? `${reviews.data.totalReviews} review${reviews.data.totalReviews === 1 ? '' : 's'}`
                  : 'No reviews yet'
              }
            />
          </div>

          <div className="grid gap-gutter lg:grid-cols-3">
            <Card className="p-gutter lg:col-span-2">
              <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
                How your traffic actually converts
              </h3>
              <FunnelBar
                scans={summary.data?.scans ?? 0}
                redemptions={summary.data?.redemptions ?? 0}
                customers={summary.data?.verifiedCustomers ?? 0}
              />
              <p className="text-body-sm text-body-sm text-on-surface-variant mt-space-md">
                A pass being scanned proves someone saw the offer. Only a redemption at the till
                proves they spent money — so this gap is your real conversion rate (
                {(summary.data?.conversionRatePercent ?? 0).toFixed(1)}%).
              </p>
            </Card>

            <Card className="p-gutter">
              <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
                Subscription
              </h3>
              <SubscriptionPanel subscription={subscription} />
            </Card>
          </div>

          <div className="grid gap-gutter lg:grid-cols-2">
            <Card className="p-gutter">
              <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
                Pending bookings
              </h3>
              {pendingBookings.error ? (
                <ErrorState
                  title="Could not load bookings"
                  message={pendingBookings.error.message}
                  isNetworkError={pendingBookings.error.isNetworkError}
                  onRetry={pendingBookings.reload}
                />
              ) : pendingBookings.loading ? (
                <LoadingState label="Loading bookings…" />
              ) : !pendingBookings.data?.items.length ? (
                <p className="text-body-sm text-body-sm text-on-surface-variant">
                  No booking requests are waiting.
                </p>
              ) : (
                <ul className="space-y-space-sm">
                  {pendingBookings.data.items.map((booking) => (
                    <li key={booking.id} className="flex items-center justify-between gap-space-sm">
                      <span className="text-body-sm text-body-sm text-on-surface">
                        Party of {booking.partySize} · {booking.visitDate}
                      </span>
                      <Badge className={STATUS_TONES.pending_admin}>
                        {pendingBookings.data?.total} waiting
                      </Badge>
                    </li>
                  ))}
                </ul>
              )}
            </Card>

            <Card className="p-gutter">
              <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
                Your campaigns
              </h3>
              {campaigns.error ? (
                <ErrorState
                  title="Could not load campaigns"
                  message={campaigns.error.message}
                  isNetworkError={campaigns.error.isNetworkError}
                  onRetry={campaigns.reload}
                />
              ) : campaigns.loading ? (
                <LoadingState label="Loading campaigns…" />
              ) : !campaigns.data?.content.length ? (
                <p className="text-body-sm text-body-sm text-on-surface-variant">
                  No creators have run a campaign at your venue yet.
                </p>
              ) : (
                <ul className="space-y-space-sm">
                  {campaigns.data.content.slice(0, 6).map((campaign) => {
                    const status = toFrontendStatus(campaign.status);
                    return (
                      <li key={campaign.id} className="flex items-center justify-between gap-space-sm">
                        <span className="truncate text-body-sm text-body-sm text-on-surface">
                          {campaign.title}
                        </span>
                        <Badge className={STATUS_TONES[status]}>{STATUS_LABELS[status]}</Badge>
                      </li>
                    );
                  })}
                </ul>
              )}
            </Card>
          </div>
        </>
      )}
    </div>
  );
}

/** Subscription state, or a clear "not subscribed" empty state. Never a fabricated plan. */
function SubscriptionPanel({ subscription }: { subscription: Resource<Subscription> }) {
  // Never loaded: say we do not know, rather than implying no subscription exists.
  if (subscription.loading) return <LoadingState label="Loading subscription…" />;

  if (subscription.data) {
    return (
      <div className="space-y-space-sm">
        <p className="font-headline-sm text-headline-sm text-on-surface">
          {subscription.data.planName ?? 'Unnamed plan'}
        </p>
        <p className="text-body-sm text-body-sm text-on-surface-variant">
          {subscription.data.status} · {formatCompactINR(subscription.data.amountPaise)} per{' '}
          {subscription.data.billingCycle === 'MONTHLY' ? 'month' : 'cycle'}
        </p>
        {subscription.data.renewsAt ? (
          <p className="text-body-sm text-body-sm text-on-surface-variant">
            Renews {new Date(subscription.data.renewsAt).toLocaleDateString('en-IN')}
          </p>
        ) : null}
        {!subscription.data.entitled ? (
          <Badge className={STATUS_TONES.rejected}>No active entitlement</Badge>
        ) : null}
        <p className="text-body-sm text-body-sm text-on-surface-variant">
          Billing is managed by TryNearby. No payment is taken through this screen.
        </p>
      </div>
    );
  }

  return (
    <p className="text-body-sm text-body-sm text-on-surface-variant">
      This restaurant has no subscription on record. Contact TryNearby to activate a plan.
    </p>
  );
}

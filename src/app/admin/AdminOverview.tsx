/**
 * Admin overview — platform health and the approval queue.
 *
 * <p>All figures come from the backend's aggregate report endpoints. The two headline numbers are
 * deliberately *not* the same: scans and verified customers are different populations, and the
 * backend derives verified customers from redemptions — never from scans.
 */

import { useCallback } from 'react';
import { Link } from 'react-router-dom';

import { useResource } from '@/app/useResource';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  FunnelBar,
  LoadingState,
  SectionHeading,
  StatTile,
} from '@/app/ui';
import { campaignsApi, toFrontendStatus } from '@/services/backend/campaigns';
import { reportsApi } from '@/services/backend/reports';
import { STATUS_TONES } from '@/services/campaignWorkflow';
import { formatRelative } from '@/utils/format';

export function AdminOverview() {
  const summary = useResource(useCallback(() => reportsApi.platformSummary(), []), []);
  const pending = useResource(
    useCallback(() => campaignsApi.listForAdmin({ status: 'PENDING' }), []),
    [],
  );

  const refreshing = summary.loading || pending.loading;

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Platform overview"
        subtitle="Live figures from the event ledger, scoped to the backend's reporting window."
        action={
          <div className="flex items-center gap-space-sm">
            {refreshing ? (
              <span className="text-body-sm text-body-sm text-on-surface-variant">Refreshing…</span>
            ) : null}
            <Button
              variant="secondary"
              onClick={() => {
                summary.reload();
                pending.reload();
              }}
            >
              Refresh
            </Button>
            {/* Deep-links to the pending queue, so "Review queue" lands on the work rather than on
                the full list. */}
            <Link
              to="/trynearby/campaigns?status=PENDING"
              className="font-label-md text-label-md text-primary hover:underline"
            >
              Review queue
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
        <LoadingState label="Loading platform metrics…" />
      ) : (
        <>
          <div className="grid gap-space-md sm:grid-cols-2 lg:grid-cols-4">
            <StatTile
              label="QR & code scans"
              value={(summary.data?.totalScans ?? 0).toLocaleString('en-IN')}
              hint="Interest only — never counted as a customer"
              tone="muted"
            />
            <StatTile
              label="Verified customers"
              value={(summary.data?.totalVerifiedCustomers ?? 0).toLocaleString('en-IN')}
              hint="Distinct diners, derived from redemptions"
              tone="primary"
            />
            <StatTile
              label="Redemptions"
              value={(summary.data?.totalRedemptions ?? 0).toLocaleString('en-IN')}
              hint="Settled at the till"
            />
            <StatTile
              label="Restaurants · creators"
              value={`${summary.data?.totalRestaurants ?? 0} · ${summary.data?.totalCreators ?? 0}`}
              hint={`${summary.data?.activeSubscriptions ?? 0} active subscriptions`}
            />
          </div>

          <Card className="p-gutter">
            <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
              Attention funnel — all campaigns
            </h3>
            <FunnelBar
              scans={summary.data?.totalScans ?? 0}
              redemptions={summary.data?.totalRedemptions ?? 0}
              customers={summary.data?.totalVerifiedCustomers ?? 0}
            />
            <p className="text-body-sm text-body-sm text-on-surface-variant mt-space-md">
              Conversion {(summary.data?.conversionRatePercent ?? 0).toFixed(1)}% of scans became a
              verified customer. Both gaps are real and are the reason the numbers do not match.
            </p>
          </Card>
        </>
      )}

      <div className="grid gap-gutter lg:grid-cols-2">
        <Card className="p-gutter">
          <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
            Awaiting review
          </h3>
          {pending.error ? (
            <ErrorState
              title="Could not load the review queue"
              message={pending.error.message}
              isNetworkError={pending.error.isNetworkError}
              onRetry={pending.reload}
            />
          ) : pending.loading ? (
            <LoadingState label="Loading the review queue…" />
          ) : !pending.data?.content.length ? (
            <EmptyState title="The queue is clear." hint="No campaigns are waiting for review." />
          ) : (
            <ul className="space-y-space-sm">
              {pending.data.content.map((campaign) => (
                <li key={campaign.id} className="flex items-start justify-between gap-space-sm">
                  <div>
                    <p className="font-label-md text-label-md text-on-surface">{campaign.title}</p>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {campaign.creatorName} → {campaign.restaurantName}
                    </p>
                  </div>
                  <Badge className={STATUS_TONES[toFrontendStatus(campaign.status)]}>
                    {formatRelative(campaign.submittedAt ?? campaign.createdAt)}
                  </Badge>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card className="p-gutter">
          <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
            Campaign status
          </h3>
          {summary.loading ? (
            <LoadingState label="Loading campaign totals…" />
          ) : (
            <ul className="space-y-space-sm">
              <li className="flex items-center justify-between gap-space-sm">
                <span className="text-body-md text-body-md text-on-surface">Live</span>
                <Badge className={STATUS_TONES.live}>
                  {summary.data?.activeCampaigns ?? 0}
                </Badge>
              </li>
              <li className="flex items-center justify-between gap-space-sm">
                <span className="text-body-md text-body-md text-on-surface">Pending review</span>
                <Badge className={STATUS_TONES.pending_admin}>
                  {summary.data?.pendingCampaigns ?? 0}
                </Badge>
              </li>
              <li className="flex items-center justify-between gap-space-sm">
                <span className="text-body-md text-body-md text-on-surface">Rejected</span>
                <Badge className={STATUS_TONES.rejected}>
                  {summary.data?.rejectedCampaigns ?? 0}
                </Badge>
              </li>
              <li className="flex items-center justify-between gap-space-sm">
                <span className="text-body-md text-body-md text-on-surface">Bookings</span>
                <Badge>{summary.data?.totalBookings ?? 0}</Badge>
              </li>
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}

/**
 * Creator dashboard.
 *
 * <p>The funnel is shown per campaign, not as one lifetime total, because the honest question a
 * creator asks is "which of my campaigns actually drove visits?" — a blended number would let a
 * dead campaign hide behind a successful one.
 *
 * <p>All figures come from the creator-scoped report endpoints. Verified customers are derived
 * from redemptions on the server, so the gap between the scan and customer bars is real.
 */

import { useCallback } from 'react';
import { Link } from 'react-router-dom';

import { useAuthStore } from '@/app/authStore';
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
import { STATUS_LABELS, STATUS_TONES } from '@/services/campaignWorkflow';
import { formatCompactINR } from '@/utils/format';

export function CreatorDashboard() {
  const displayName = useAuthStore((s) => s.user?.displayName);
  const handle = useAuthStore((s) => s.user?.creatorHandle);

  const summary = useResource(useCallback(() => reportsApi.creatorSummary(), []), []);
  const performance = useResource(useCallback(() => reportsApi.creatorCampaigns(), []), []);
  const campaigns = useResource(useCallback(() => campaignsApi.listForCreator(), []), []);

  const byId = new Map((performance.data ?? []).map((row) => [row.id, row]));
  const active = (campaigns.data?.content ?? []).filter((c) => c.status === 'ACTIVE');
  const awaiting = (campaigns.data?.content ?? []).filter((c) => c.status === 'PENDING').length;
  const total = campaigns.data?.totalElements ?? 0;

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title={displayName ?? 'Your dashboard'}
        subtitle={handle ? `@${handle}` : 'Your campaigns and their verified results'}
        action={
          <div className="flex items-center gap-space-sm">
            <Button variant="secondary" onClick={summary.reload}>
              Refresh
            </Button>
            <Link to="/creator/new">
              <Button>New campaign</Button>
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
        <div className="grid gap-space-md sm:grid-cols-2 lg:grid-cols-4">
          <StatTile
            label="Verified customers"
            value={(summary.data?.verifiedCustomers ?? 0).toLocaleString('en-IN')}
            hint="Distinct diners who actually redeemed"
            tone="primary"
          />
          <StatTile
            label="Passes scanned"
            value={(summary.data?.scans ?? 0).toLocaleString('en-IN')}
            hint="Reach — not customers"
            tone="muted"
          />
          <StatTile
            label="Discount provided"
            value={formatCompactINR(summary.data?.discountPaise ?? 0)}
            hint={`${summary.data?.redemptions ?? 0} redemptions`}
          />
          <StatTile
            label="Live campaigns"
            value={String(active.length)}
            hint={`${total} total · ${awaiting} awaiting review`}
          />
        </div>
      )}

      {campaigns.loading ? (
        <LoadingState label="Loading your campaigns…" />
      ) : active.length === 0 ? (
        <EmptyState
          title="No live campaigns"
          hint="Submit a campaign and it will appear in the admin review queue."
        />
      ) : (
        <div className="space-y-gutter">
          {active.map((campaign) => {
            const status = toFrontendStatus(campaign.status);
            const m = byId.get(campaign.id);

            return (
              <Card key={campaign.id} className="p-gutter">
                <div className="flex flex-wrap items-start justify-between gap-space-md">
                  <div>
                    <div className="flex flex-wrap items-center gap-space-sm">
                      <h3 className="font-headline-sm text-headline-sm text-on-surface">
                        {campaign.title}
                      </h3>
                      <Badge className={STATUS_TONES[status]}>{STATUS_LABELS[status]}</Badge>
                    </div>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {campaign.restaurantName} · {campaign.offerText}
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
                    scans={m?.scans ?? 0}
                    redemptions={m?.redemptions ?? 0}
                    customers={m?.verifiedCustomers ?? 0}
                  />
                  <dl className="grid grid-cols-2 gap-space-sm self-center text-body-sm text-body-sm">
                    <div>
                      <dt className="text-on-surface-variant">Target reached</dt>
                      <dd className="text-on-surface">
                        {m?.verifiedCustomers ?? 0} / {campaign.targetCustomers}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-on-surface-variant">Discount given</dt>
                      <dd className="text-on-surface">
                        {formatCompactINR(m?.discountPaise ?? 0)}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-on-surface-variant">Scan → customer</dt>
                      <dd className="text-on-surface">
                        {(m?.conversionRatePercent ?? 0).toFixed(1)}%
                      </dd>
                    </div>
                    <div>
                      <dt className="text-on-surface-variant">Redemptions</dt>
                      <dd className="text-on-surface">{m?.redemptions ?? 0}</dd>
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

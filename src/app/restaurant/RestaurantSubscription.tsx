/**
 * Restaurant subscription and plan catalogue.
 *
 * <p>Read-only on purpose: plan and status changes are administrator actions on the backend, and
 * no payment is taken through this screen. The plan list is whatever the server currently offers.
 */

import { useCallback } from 'react';

import { useResource } from '@/app/useResource';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  LoadingState,
  SectionHeading,
} from '@/app/ui';
import { subscriptionsApi } from '@/services/backend/community';
import { formatINR, formatDate } from '@/utils/format';

export function RestaurantSubscription() {
  const current = useResource(useCallback(() => subscriptionsApi.current(), []), []);
  const plans = useResource(useCallback(() => subscriptionsApi.plans(), []), []);

  // A 404 here means "never subscribed", which is an empty state rather than a failure.
  const notSubscribed = current.error?.status === 404;

  return (
    <div className="max-w-3xl space-y-gutter">
      <SectionHeading
        title="Subscription"
        subtitle="Your plan, status and renewal dates as recorded by TryNearby."
        action={
          <Button variant="secondary" onClick={current.reload}>
            Refresh
          </Button>
        }
      />

      {current.error && !notSubscribed ? (
        <ErrorState
          message={current.error.message}
          isNetworkError={current.error.isNetworkError}
          onRetry={current.reload}
        />
      ) : current.loading ? (
        <LoadingState label="Loading your subscription…" />
      ) : notSubscribed || !current.data ? (
        <EmptyState
          title="No subscription on record"
          hint="Contact TryNearby to activate a plan for your restaurant."
        />
      ) : (
        <Card className="p-gutter space-y-space-sm">
          <div className="flex flex-wrap items-center justify-between gap-space-sm">
            <h3 className="font-headline-sm text-headline-sm text-on-surface">
              {current.data.planName ?? current.data.planCode ?? 'Unnamed plan'}
            </h3>
            <Badge
              className={
                current.data.entitled
                  ? 'border-primary bg-primary text-on-primary'
                  : 'border-error/40 bg-error-container text-on-error-container'
              }
            >
              {current.data.status}
            </Badge>
          </div>
          <p className="text-body-md text-body-md text-on-surface">
            {formatINR(current.data.amountPaise / 100)} per{' '}
            {current.data.billingCycle === 'MONTHLY' ? 'month' : 'cycle'}
          </p>
          <p className="text-body-sm text-body-sm text-on-surface-variant">
            Started {formatDate(current.data.startedAt.slice(0, 10))}
            {current.data.renewsAt
              ? ` · renews ${formatDate(current.data.renewsAt.slice(0, 10))}`
              : ''}
            {current.data.cancelledAt
              ? ` · cancelled ${formatDate(current.data.cancelledAt.slice(0, 10))}`
              : ''}
          </p>
          {!current.data.entitled ? (
            <p className="text-body-sm text-body-sm text-on-surface-variant">
              This plan is not currently granting entitlement.
            </p>
          ) : null}
          <p className="text-body-sm text-body-sm text-on-surface-variant">
            Plan and status changes are made by TryNearby. This screen does not take payments.
          </p>
        </Card>
      )}

      <SectionHeading title="Available plans" subtitle="Currently offered by TryNearby." />

      {plans.error ? (
        <ErrorState
          message={plans.error.message}
          isNetworkError={plans.error.isNetworkError}
          onRetry={plans.reload}
        />
      ) : plans.loading ? (
        <LoadingState label="Loading plans…" />
      ) : !plans.data?.length ? (
        <EmptyState title="No plans are on offer right now." />
      ) : (
        <ul className="grid gap-space-md sm:grid-cols-2">
          {plans.data.map((plan) => (
            <Card as="li" key={plan.id} className="p-gutter">
              <h4 className="font-label-md text-label-md text-on-surface">{plan.name}</h4>
              <p className="font-display-sm text-display-sm text-primary mt-1">
                {formatINR(plan.monthlyPricePaise / 100)}
                <span className="text-body-sm text-body-sm text-on-surface-variant">
                  {' '}
                  / month
                </span>
              </p>
              <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">
                Up to {plan.maxCampaigns} campaigns
              </p>
              {plan.features ? (
                <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">
                  {plan.features}
                </p>
              ) : null}
            </Card>
          ))}
        </ul>
      )}
    </div>
  );
}
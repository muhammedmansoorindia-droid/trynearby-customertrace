/**
 * Platform subscription management.
 *
 * <p>Plan and status changes are administrator actions on the backend, and the restaurant is told
 * about each one by notification. No payment is taken or simulated here: `external_ref` stays
 * reserved for a future provider, and this screen only records decisions an administrator makes.
 */
import { useCallback, useState } from 'react';

import { useMutation, useResource } from '@/app/useResource';
import {
  Badge,
  Button,
  Callout,
  Card,
  EmptyState,
  ErrorState,
  Field,
  LoadingState,
  SectionHeading,
  inputClass,
} from '@/app/ui';
import { adminApi, subscriptionsApi } from '@/services/backend/community';
import type { AdminSubscription } from '@/services/backend/community';

/**
 * Mirrors `SubscriptionService.ALLOWED_TRANSITIONS`.
 *
 * <p>Duplicated deliberately as a *filter*, never as authority: it stops the UI offering an
 * impossible change, and the backend still decides. Anything absent here is simply not offered.
 */
const ALLOWED_TRANSITIONS: Record<string, string[]> = {
  TRIALING: ['ACTIVE', 'CANCELLED'],
  ACTIVE: ['PAST_DUE', 'CANCELLED'],
  PAST_DUE: ['ACTIVE', 'CANCELLED'],
  CANCELLED: [],
};

const ENTITLED_BADGE = 'border-primary bg-primary text-on-primary';
const PLAIN_BADGE = 'border-outline-variant/40 bg-surface-container text-on-surface-variant';

export function AdminSubscriptions() {
  const [notice, setNotice] = useState<string | null>(null);
  const [planChoice, setPlanChoice] = useState<Record<number, string>>({});

  const list = useResource(useCallback(() => adminApi.subscriptions(), []), []);
  const plans = useResource(useCallback(() => subscriptionsApi.plans(), []), []);

  const changePlan = useMutation(async (id: number, planId: number) =>
    adminApi.changeSubscriptionPlan(id, planId),
  );
  const changeStatus = useMutation(async (id: number, status: string) =>
    adminApi.changeSubscriptionStatus(id, status),
  );

  const mutationError = changePlan.error ?? changeStatus.error ?? null;

  /** Reloads both lists; a plan change can alter what the status column shows. */
  function refresh() {
    list.reload();
    plans.reload();
  }

  async function applyPlan(row: AdminSubscription) {
    const chosen = planChoice[row.id];
    if (!chosen) return;
    const result = await changePlan.run(row.id, Number(chosen));
    if (result) {
      setNotice(`Plan updated for ${row.restaurantName ?? 'the restaurant'}.`);
      refresh();
    }
  }

  async function applyStatus(row: AdminSubscription, status: string) {
    const result = await changeStatus.run(row.id, status);
    if (result) {
      setNotice(`${row.restaurantName ?? 'The restaurant'} is now ${status}.`);
      refresh();
    }
  }
return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Subscriptions"
        subtitle="Every subscription on the platform, with the plan and status that decide entitlements."
        action={
          <Button variant="secondary" onClick={refresh}>
            Refresh
          </Button>
        }
      />

      {notice ? <Callout tone="success">{notice}</Callout> : null}
      {mutationError ? <Callout tone="error">{mutationError.message}</Callout> : null}

      {list.error ? (
        <ErrorState
          message={list.error.message}
          isNetworkError={list.error.isNetworkError}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <LoadingState label="Loading subscriptions…" />
      ) : !list.data?.length ? (
        <EmptyState
          title="No subscriptions on record"
          hint="A restaurant appears here once one is created for it."
        />
      ) : (
        <ul className="space-y-space-sm">
          {list.data.map((row) => {
            const targets = ALLOWED_TRANSITIONS[row.status] ?? [];
            return (
              <Card as="li" key={row.id} className="p-gutter space-y-space-sm">
                <div className="flex flex-wrap items-start justify-between gap-space-sm">
                  <div className="min-w-0">
                    <p className="font-label-md text-label-md text-on-surface">
                      {row.restaurantName ?? `Restaurant #${row.restaurantId}`}
                    </p>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {row.planName ?? row.planCode ?? 'No plan'} · {row.billingCycle}
                      {row.renewsAt ? ` · renews ${row.renewsAt.slice(0, 10)}` : ''}
                    </p>
                  </div>
                  <Badge className={row.entitled ? ENTITLED_BADGE : PLAIN_BADGE}>
                    {row.entitled ? `${row.status} · entitled` : row.status}
                  </Badge>
                </div>

                <div className="flex flex-wrap items-end gap-space-sm">
                  <Field
                    label="Change plan"
                    hint={`Currently ${row.planName ?? row.planCode ?? 'no plan'}.`}
                  >
                    <select
                      className={inputClass}
                      aria-label={`Plan for ${row.restaurantName ?? row.restaurantId}`}
                      value={planChoice[row.id] ?? ''}
                      onChange={(event) =>
                        setPlanChoice((prev) => ({ ...prev, [row.id]: event.target.value }))
                      }
                    >
                      <option value="">Select a plan…</option>
                      {(plans.data ?? []).map((plan) => (
                        <option key={plan.id} value={plan.id}>
                          {plan.name}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Button
                    variant="secondary"
                    disabled={!planChoice[row.id] || changePlan.pending}
                    onClick={() => applyPlan(row)}
                  >
                    Apply plan
                  </Button>

                  {/* Only legal transitions are offered; the server still has the final say. */}
                  {targets.length === 0 ? (
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      This subscription is closed — it cannot be changed further.
                    </p>
                  ) : (
                    targets.map((status) => (
                      <Button
                        key={status}
                        variant={status === 'CANCELLED' ? 'danger' : 'secondary'}
                        disabled={changeStatus.pending}
                        onClick={() => applyStatus(row, status)}
                      >
                        Mark {status.toLowerCase()}
                      </Button>
                    ))
                  )}
                </div>
              </Card>
            );
          })}
        </ul>
      )}

      <p className="text-body-sm text-body-sm text-on-surface-variant">
        No payment is processed here. Plan and status changes are recorded decisions, and the
        restaurant receives a notification for each one.
      </p>
    </div>
  );
}
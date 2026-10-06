/**
 * Restaurant and creator directory, with profile status control.
 *
 * <p>Reads the same summary endpoints the assignment pickers use, which carry status, subscription
 * and workload. Suspending a profile hides it from new work but deletes nothing, so this screen
 * never has to warn about data loss — and reactivating genuinely undoes the change.
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
  LoadingState,
  SectionHeading,
} from '@/app/ui';
import { adminApi } from '@/services/backend/community';

const ACTIVE_BADGE = 'border-primary bg-primary text-on-primary';
const SUSPENDED_BADGE = 'border-outline-variant/40 bg-surface-container text-on-surface-variant';

export function AdminDirectory() {
  const [tab, setTab] = useState<'restaurants' | 'creators'>('restaurants');
  const [notice, setNotice] = useState<string | null>(null);

  const restaurants = useResource(useCallback(() => adminApi.restaurants(), []), []);
  const creators = useResource(useCallback(() => adminApi.creators(), []), []);

  const setRestaurantStatus = useMutation(async (id: number, status: string) =>
    adminApi.setRestaurantStatus(id, status),
  );
  const setCreatorStatus = useMutation(async (id: number, status: string) =>
    adminApi.setCreatorStatus(id, status),
  );

  const mutationError = setRestaurantStatus.error ?? setCreatorStatus.error ?? null;
  const refreshing = restaurants.loading || creators.loading;

  /**
   * Flips the status and only reports success once the backend has confirmed it.
   *
   * <p>A failed call leaves `notice` untouched, so the screen can never claim a change the server
   * refused.
   */
  async function toggle(
    run: (id: number, status: string) => Promise<unknown>,
    reload: () => void,
    id: number,
    current: string,
    label: string,
  ) {
    const next = current === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
    const result = await run(id, next);
    if (result) {
      setNotice(`${label} is now ${next === 'ACTIVE' ? 'active' : 'suspended'}.`);
      reload();
    }
  }

  const tabs = [
    { id: 'restaurants' as const, label: 'Restaurants', count: restaurants.data?.length ?? 0 },
    { id: 'creators' as const, label: 'Creators', count: creators.data?.length ?? 0 },
  ];

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Directory"
        subtitle="Restaurants and creators on the platform, with the status each one is in."
        action={
          <Button
            variant="secondary"
            onClick={() => {
              restaurants.reload();
              creators.reload();
            }}
          >
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />

      <div role="tablist" aria-label="Directory section" className="ct-scroll-x flex gap-space-xs">
        {tabs.map((option) => {
          const selected = tab === option.id;
          return (
            <button
              key={option.id}
              role="tab"
              aria-selected={selected}
              type="button"
              onClick={() => setTab(option.id)}
              className={`whitespace-nowrap rounded-full border px-space-md py-space-sm font-label-sm text-label-sm transition-colors ${
                selected
                  ? 'bg-primary text-on-primary border-primary'
                  : 'bg-surface-container-lowest text-on-surface-variant border-outline-variant/40 hover:bg-surface-container-high'
              }`}
            >
              {option.label} ({option.count})
            </button>
          );
        })}
      </div>

      {notice ? <Callout tone="success">{notice}</Callout> : null}
      {mutationError ? <Callout tone="error">{mutationError.message}</Callout> : null}

      {tab === 'restaurants' ? (
        restaurants.error ? (
          <ErrorState
            message={restaurants.error.message}
            isNetworkError={restaurants.error.isNetworkError}
            onRetry={restaurants.reload}
          />
        ) : restaurants.loading ? (
          <LoadingState label="Loading restaurants…" />
        ) : !restaurants.data?.length ? (
          <EmptyState
            title="No restaurants yet"
            hint="A restaurant appears here once it has an account."
          />
        ) : (
          <ul className="space-y-space-sm">
            {restaurants.data.map((restaurant) => (
              <Card as="li" key={restaurant.id} className="p-gutter">
                <div className="flex flex-wrap items-start justify-between gap-space-sm">
                  <div className="min-w-0">
                    <p className="font-label-md text-label-md text-on-surface">
                      {restaurant.name}
                    </p>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {restaurant.city}
                      {restaurant.region ? `, ${restaurant.region}` : ''} ·{' '}
                      {restaurant.averageRating == null
                        ? 'no ratings yet'
                        : `${restaurant.averageRating.toFixed(1)}★ from ${restaurant.ratingCount}`}
                    </p>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {restaurant.activeCreatorCount} active creator
                      {restaurant.activeCreatorCount === 1 ? '' : 's'} ·{' '}
                      {restaurant.campaignCount} campaign
                      {restaurant.campaignCount === 1 ? '' : 's'} ·{' '}
                      {restaurant.subscriptionStatus
                        ? `${restaurant.subscriptionPlan ?? 'Plan'} (${restaurant.subscriptionStatus})`
                        : 'no subscription on record'}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-space-sm">
                    <Badge
                      className={restaurant.status === 'ACTIVE' ? ACTIVE_BADGE : SUSPENDED_BADGE}
                    >
                      {restaurant.status === 'ACTIVE' ? 'Active' : 'Suspended'}
                    </Badge>
                    <Button
                      variant="secondary"
                      disabled={setRestaurantStatus.pending}
                      onClick={() =>
                        toggle(
                          setRestaurantStatus.run,
                          restaurants.reload,
                          restaurant.id,
                          restaurant.status,
                          restaurant.name,
                        )
                      }
                    >
                      {restaurant.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'}
                    </Button>
                  </div>
                </div>
              </Card>
            ))}
          </ul>
        )
      ) : creators.error ? (
        <ErrorState
          message={creators.error.message}
          isNetworkError={creators.error.isNetworkError}
          onRetry={creators.reload}
        />
      ) : creators.loading ? (
        <LoadingState label="Loading creators…" />
      ) : !creators.data?.length ? (
        <EmptyState title="No creators yet" hint="A creator appears here once they sign up." />
      ) : (
        <ul className="space-y-space-sm">
          {creators.data.map((creator) => (
            <Card as="li" key={creator.id} className="p-gutter">
              <div className="flex flex-wrap items-start justify-between gap-space-sm">
                <div className="min-w-0">
                  <p className="font-label-md text-label-md text-on-surface">
                    {creator.displayName}
                    {creator.verified ? ' · verified' : ''}
                  </p>
                  <p className="text-body-sm text-body-sm text-on-surface-variant">
                    {creator.handle}
                    {creator.city ? ` · ${creator.city}` : ''}
                    {creator.niche ? ` · ${creator.niche}` : ''}
                  </p>
                  <p className="text-body-sm text-body-sm text-on-surface-variant">
                    {creator.followers.toLocaleString('en-IN')} followers ·{' '}
                    {creator.activeRestaurantCount} active assignment
                    {creator.activeRestaurantCount === 1 ? '' : 's'} ·{' '}
                    {creator.campaignCount} campaign{creator.campaignCount === 1 ? '' : 's'}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-space-sm">
                  <Badge className={creator.status === 'ACTIVE' ? ACTIVE_BADGE : SUSPENDED_BADGE}>
                    {creator.status === 'ACTIVE' ? 'Active' : 'Suspended'}
                  </Badge>
                  <Button
                    variant="secondary"
                    disabled={setCreatorStatus.pending}
                    onClick={() =>
                      toggle(
                        setCreatorStatus.run,
                        creators.reload,
                        creator.id,
                        creator.status,
                        creator.displayName,
                      )
                    }
                  >
                    {creator.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'}
                  </Button>
                </div>
              </div>
            </Card>
          ))}
        </ul>
      )}

      <p className="text-body-sm text-body-sm text-on-surface-variant">
        Suspending a profile stops new work. Campaigns, bookings and assignments are kept, so
        reactivating restores it exactly.
      </p>
    </div>
  );
}

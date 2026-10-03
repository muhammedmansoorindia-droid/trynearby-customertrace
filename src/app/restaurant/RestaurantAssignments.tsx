/**
 * Creators assigned to this restaurant.
 *
 * <p>A restaurant may assign creators to itself and end those assignments, but it cannot assign a
 * creator to some other venue — that is enforced server-side by resolving the restaurant from the
 * access token rather than the request body.
 */

import { useCallback } from 'react';

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
import { assignmentsApi } from '@/services/backend/community';
import { formatRelative } from '@/utils/format';

export function RestaurantAssignments() {
  const list = useResource(useCallback(() => assignmentsApi.listForRestaurant(), []), []);

  const deactivate = useMutation(async (id: number) =>
    assignmentsApi.deactivateAsRestaurant(id),
  );

  const assignments = list.data ?? [];

  return (
    <div className="max-w-3xl space-y-gutter">
      <SectionHeading
        title="Assigned creators"
        subtitle="Creators who may run campaigns at your venue."
        action={
          <Button variant="secondary" onClick={list.reload}>
            Refresh
          </Button>
        }
      />

      {deactivate.error ? <Callout tone="error">{deactivate.error.message}</Callout> : null}

      {list.error ? (
        <ErrorState
          message={list.error.message}
          isNetworkError={list.error.isNetworkError}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <LoadingState label="Loading assignments…" />
      ) : assignments.length === 0 ? (
        <EmptyState
          title="No creators assigned"
          hint="TryNearby assigns creators to restaurants. Ask them to add one."
        />
      ) : (
        <ul className="space-y-space-sm">
          {assignments.map((assignment) => (
            <Card as="li" key={assignment.id} className="p-gutter">
              <div className="flex flex-wrap items-center justify-between gap-space-sm">
                <div>
                  <p className="font-label-md text-label-md text-on-surface">
                    Creator #{assignment.creatorId}
                  </p>
                  <p className="text-body-sm text-body-sm text-on-surface-variant">
                    Assigned {formatRelative(assignment.createdAt)}
                  </p>
                </div>
                <div className="flex items-center gap-space-sm">
                  <Badge
                    className={
                      assignment.active
                        ? 'border-primary bg-primary text-on-primary'
                        : 'border-outline-variant/40 bg-surface-container text-on-surface-variant'
                    }
                  >
                    {assignment.active ? 'Active' : 'Ended'}
                  </Badge>
                  {assignment.active ? (
                    <Button
                      variant="secondary"
                      disabled={deactivate.pending}
                      onClick={async () => {
                        const result = await deactivate.run(assignment.id);
                        if (result) list.reload();
                      }}
                    >
                      End assignment
                    </Button>
                  ) : null}
                </div>
              </div>
            </Card>
          ))}
        </ul>
      )}

      <p className="text-body-sm text-body-sm text-on-surface-variant">
        Ending an assignment stops new campaigns; campaigns already live are unaffected.
      </p>
    </div>
  );
}
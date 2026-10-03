/**
 * Table bookings addressed to the restaurant.
 *
 * <p>Status changes are sent to the backend and the list is refetched only after it confirms. Which
 * transitions are offered mirrors the backend's allow-list; a refusal is shown verbatim rather than
 * silently swallowed, because the server is the authority on what is legal.
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
import {
  allowedNextStatuses,
  bookingsApi,
  type BookingStatus,
} from '@/services/backend/bookings';
import { cn } from '@/utils';
import { formatDate, formatRelative } from '@/utils/format';

const TONES: Record<BookingStatus, string> = {
  REQUESTED: 'bg-primary-fixed text-on-primary-fixed-variant border-primary-fixed',
  CONFIRMED: 'bg-secondary-container text-on-secondary-fixed border-secondary/25',
  COMPLETED: 'bg-surface-container text-on-surface-variant border-outline-variant/40',
  CANCELLED: 'bg-error-container text-on-error-container border-error/25',
  DECLINED: 'bg-error-container text-on-error-container border-error/25',
};

const LABELS: Record<BookingStatus, string> = {
  REQUESTED: 'Requested',
  CONFIRMED: 'Confirmed',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  DECLINED: 'Declined',
};

export function RestaurantBookings() {
  const [status, setStatus] = useState<BookingStatus | ''>('');
  const [page, setPage] = useState(0);

  const list = useResource(
    useCallback(
      () => bookingsApi.list('restaurant', { status: status || undefined, page, size: 20 }),
      [status, page],
    ),
    [status, page],
  );

  const change = useMutation(async (id: number, next: BookingStatus) =>
    bookingsApi.changeStatus('restaurant', id, next),
  );

  const bookings = list.data?.items ?? [];
  const total = list.data?.total ?? 0;
  const size = list.data?.size ?? 20;
  const lastPage = Math.max(Math.ceil(total / size) - 1, 0);

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Table bookings"
        subtitle={
          status
            ? `Filtered by ${LABELS[status].toLowerCase()}.`
            : 'Creator table requests for your venue.'
        }
        action={
          <div className="flex flex-wrap items-center gap-space-sm">
            <select
              aria-label="Filter by status"
              className="rounded-lg border border-outline-variant/50 bg-surface-container-lowest px-space-sm py-space-sm text-body-md text-body-md text-on-surface"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as BookingStatus | '');
                setPage(0);
              }}
            >
              <option value="">All statuses</option>
              {Object.keys(LABELS).map((key) => (
                <option key={key} value={key}>
                  {LABELS[key as BookingStatus]}
                </option>
              ))}
            </select>
            <Button variant="secondary" onClick={list.reload}>
              Refresh
            </Button>
          </div>
        }
      />

      {change.error ? <Callout tone="error">{change.error.message}</Callout> : null}

      {list.error ? (
        <ErrorState
          message={list.error.message}
          isNetworkError={list.error.isNetworkError}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <LoadingState label="Loading bookings…" />
      ) : bookings.length === 0 ? (
        <EmptyState title="No bookings" hint="Creator table requests will land here." />
      ) : (
        <ul className="space-y-space-sm">
          {bookings.map((booking) => {
            const next = allowedNextStatuses(booking.status, 'restaurant');
            return (
              <Card as="li" key={booking.id} className="p-gutter">
                <div className="flex flex-wrap items-start justify-between gap-space-md">
                  <div>
                    <p className="font-headline-sm text-headline-sm text-on-surface">
                      {formatDate(booking.visitDate)} · party of {booking.partySize}
                    </p>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      Requested {formatRelative(booking.createdAt)}
                    </p>
                    {booking.note ? (
                      <p className="mt-space-sm rounded-lg bg-surface-container px-space-sm py-space-xs text-body-sm text-body-sm text-on-surface">
                        {booking.note}
                      </p>
                    ) : null}
                  </div>

                  <div className="flex flex-wrap items-center gap-space-sm">
                    <Badge className={TONES[booking.status]}>{LABELS[booking.status]}</Badge>
                    {next.map((target) => (
                      <Button
                        key={target}
                        variant={target === 'CONFIRMED' ? 'primary' : 'secondary'}
                        disabled={change.pending}
                        onClick={async () => {
                          const result = await change.run(booking.id, target);
                          if (result) list.reload();
                        }}
                      >
                        {target === 'CONFIRMED' ? 'Confirm' : LABELS[target]}
                      </Button>
                    ))}
                  </div>
                </div>
              </Card>
            );
          })}
        </ul>
      )}

      {total > size ? (
        <div className="flex items-center justify-between gap-space-sm">
          <Button variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <span className="text-body-sm text-body-sm text-on-surface-variant">
            Page {page + 1} of {lastPage + 1}
          </span>
          <Button variant="ghost" disabled={page >= lastPage} onClick={() => setPage(page + 1)}>
            Next
          </Button>
        </div>
      ) : null}

      <p className={cn('text-body-sm text-body-sm text-on-surface-variant')}>
        Bookings are requests, not revenue — they are tracked separately from redemptions so a
        confirmed table is never mistaken for a verified customer.
      </p>
    </div>
  );
}

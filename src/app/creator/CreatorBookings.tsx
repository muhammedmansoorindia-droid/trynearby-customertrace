/**
 * Bookings made by the signed-in creator.
 *
 * <p>A creator may only cancel its own request; confirming and completing are the restaurant's
 * decisions, so those buttons are not offered here. The backend enforces the same rule.
 */

import { useCallback, useState, type FormEvent } from 'react';

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
import {
  allowedNextStatuses,
  bookingsApi,
  type BookingStatus,
} from '@/services/backend/bookings';
import { formatDate, formatRelative } from '@/utils/format';

const LABELS: Record<BookingStatus, string> = {
  REQUESTED: 'Requested',
  CONFIRMED: 'Confirmed',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  DECLINED: 'Declined',
};

export function CreatorBookings() {
  const [restaurantId, setRestaurantId] = useState('');
  const [visitDate, setVisitDate] = useState('');
  const [partySize, setPartySize] = useState('2');
  const [note, setNote] = useState('');

  const list = useResource(useCallback(() => bookingsApi.list('creator', { size: 30 }), []), []);

  const create = useMutation(async () =>
    bookingsApi.create({
      restaurantId: Number(restaurantId),
      visitDate,
      partySize: Number(partySize),
      note: note.trim() || undefined,
    }),
  );

  const change = useMutation(async (id: number, next: BookingStatus) =>
    bookingsApi.changeStatus('creator', id, next),
  );

  // Mirrors the backend's own validation so the user is told before a round trip.
  const formError = !restaurantId
    ? 'Choose a restaurant.'
    : !visitDate
      ? 'Choose a visit date.'
      : Number(partySize) < 1 || Number(partySize) > 50
        ? 'Party size must be between 1 and 50.'
        : null;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (formError) return;
    const created = await create.run();
    if (created) {
      setRestaurantId('');
      setVisitDate('');
      setNote('');
      list.reload();
    }
  };

  const bookings = list.data?.items ?? [];

  return (
    <div className="max-w-3xl space-y-gutter">
      <SectionHeading
        title="Your bookings"
        subtitle="Table requests you have made to restaurants you are assigned to."
        action={
          <Button variant="secondary" onClick={list.reload}>
            Refresh
          </Button>
        }
      />

      <Card className="p-gutter">
        <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
          Request a table
        </h3>
        <form className="space-y-space-md" onSubmit={submit}>
          <Field label="Restaurant ID" hint="The venue you are assigned to.">
            <input
              className={inputClass}
              type="number"
              min="1"
              value={restaurantId}
              onChange={(event) => setRestaurantId(event.target.value)}
              required
            />
          </Field>
          <Field label="Visit date">
            <input
              className={inputClass}
              type="date"
              value={visitDate}
              onChange={(event) => setVisitDate(event.target.value)}
              required
            />
          </Field>
          <Field label="Party size">
            <input
              className={inputClass}
              type="number"
              min="1"
              max="50"
              value={partySize}
              onChange={(event) => setPartySize(event.target.value)}
              required
            />
          </Field>
          <Field label="Note" hint="Optional — anything the venue should know.">
            <input
              className={inputClass}
              value={note}
              onChange={(event) => setNote(event.target.value)}
              maxLength={500}
            />
          </Field>

          {formError ? (
            <p role="alert" className="text-body-sm text-body-sm text-error">
              {formError}
            </p>
          ) : null}
          {create.error ? <Callout tone="error">{create.error.message}</Callout> : null}

          <Button type="submit" disabled={Boolean(formError) || create.pending}>
            {create.pending ? 'Requesting…' : 'Request booking'}
          </Button>
        </form>
      </Card>

      {change.error ? <Callout tone="error">{change.error.message}</Callout> : null}

      {list.error ? (
        <ErrorState
          message={list.error.message}
          isNetworkError={list.error.isNetworkError}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <LoadingState label="Loading your bookings…" />
      ) : bookings.length === 0 ? (
        <EmptyState title="No bookings yet" hint="Request a table above." />
      ) : (
        <ul className="space-y-space-sm">
          {bookings.map((booking) => (
            <Card as="li" key={booking.id} className="p-gutter">
              <div className="flex flex-wrap items-center justify-between gap-space-sm">
                <div>
                  <p className="font-label-md text-label-md text-on-surface">
                    {formatDate(booking.visitDate)} · party of {booking.partySize}
                  </p>
                  <p className="text-body-sm text-body-sm text-on-surface-variant">
                    Restaurant #{booking.restaurantId} · requested{' '}
                    {formatRelative(booking.createdAt)}
                  </p>
                  {booking.note ? (
                    <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">
                      {booking.note}
                    </p>
                  ) : null}
                </div>
                <div className="flex items-center gap-space-sm">
                  <Badge>{LABELS[booking.status]}</Badge>
                  {allowedNextStatuses(booking.status, 'creator').map((target) => (
                    <Button
                      key={target}
                      variant="secondary"
                      disabled={change.pending}
                      onClick={async () => {
                        const result = await change.run(booking.id, target);
                        if (result) list.reload();
                      }}
                    >
                      {LABELS[target]}
                    </Button>
                  ))}
                </div>
              </div>
            </Card>
          ))}
        </ul>
      )}
    </div>
  );
}
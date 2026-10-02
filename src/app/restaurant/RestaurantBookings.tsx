/**
 * Table bookings requested against the restaurant's campaigns.
 *
 * Confirming is the one mutation here; it is written through the store so the creator who
 * made the request sees the status change without a refresh.
 */

import { useAppStore } from '@/app/store';
import { NoData } from '@/app/PortalLayout';
import { Badge, Button, Card, EmptyState, SectionHeading } from '@/app/ui';
import { formatDate, formatRelative } from '@/utils/format';
import { cn } from '@/utils';

const TONES = {
  requested: 'bg-primary-fixed text-on-primary-fixed-variant border-primary-fixed',
  confirmed: 'bg-secondary-container text-on-secondary-fixed border-secondary/25',
  completed: 'bg-surface-container text-on-surface-variant border-outline-variant/40',
  cancelled: 'bg-error-container text-on-error-container border-error/25',
} as const;

export function RestaurantBookings() {
  const db = useAppStore((s) => s.db);
  const restaurantId = useAppStore((s) => s.session.restaurantId);
  const confirmBooking = useAppStore((s) => s.confirmBooking);

  const restaurant = db.restaurants.find((r) => r.id === restaurantId);
  if (!restaurant) {
    return <NoData message="Switch portal and pick a restaurant to continue." />;
  }

  const bookings = db.bookings
    .filter((b) => b.restaurantId === restaurant.id)
    .sort((a, b) => a.date.localeCompare(b.date));

  const requested = bookings.filter((b) => b.status === 'requested').length;

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Table bookings"
        subtitle={
          requested > 0
            ? `${requested} request${requested === 1 ? '' : 's'} waiting on a reply.`
            : 'All requests have been answered.'
        }
      />

      {bookings.length === 0 ? (
        <EmptyState title="No bookings" hint="Creator table requests will land here." />
      ) : (
        <ul className="space-y-space-sm">
          {bookings.map((booking) => {
            const creator = db.creators.find((c) => c.id === booking.creatorId);
            const campaign = booking.campaignId
              ? db.campaigns.find((c) => c.id === booking.campaignId)
              : undefined;

            return (
              <Card as="li" key={booking.id} className="p-gutter">
                <div className="flex flex-wrap items-start justify-between gap-space-md">
                  <div>
                    <p className="font-headline-sm text-headline-sm text-on-surface">
                      {formatDate(booking.date)} · party of {booking.partySize}
                    </p>
                    <p className="text-body-sm text-body-sm text-on-surface-variant">
                      {creator?.displayName}
                      {campaign ? ` · via “${campaign.title}”` : ''}
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

                  <div className="flex items-center gap-space-sm">
                    <Badge className={TONES[booking.status]}>
                      <span className="capitalize">{booking.status}</span>
                    </Badge>
                    {booking.status === 'requested' ? (
                      <Button onClick={() => confirmBooking(booking.id)}>Confirm</Button>
                    ) : null}
                  </div>
                </div>
              </Card>
            );
          })}
        </ul>
      )}

      <p className={cn('text-body-sm text-body-sm text-on-surface-variant')}>
        Bookings are requests, not revenue — they are tracked separately from redemptions so a
        confirmed table is never mistaken for a verified customer.
      </p>
    </div>
  );
}

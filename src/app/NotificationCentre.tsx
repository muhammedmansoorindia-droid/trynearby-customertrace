/**
 * Notification inbox, shared by all three portals.
 *
 * <p>The list is scoped server-side to the signed-in user, so there is no recipient filter here
 * and no way to see another user's mail. Marking read is a backend write; the list refreshes only
 * after it succeeds.
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
import { notificationsApi } from '@/services/backend/community';
import { cn } from '@/utils';
import { formatRelative } from '@/utils/format';

export function NotificationCentre() {
  const [unreadOnly, setUnreadOnly] = useState(false);

  const list = useResource(
    useCallback(() => notificationsApi.list({ unreadOnly, size: 30 }), [unreadOnly]),
    [unreadOnly],
  );

  const markRead = useMutation(async (id: number) => notificationsApi.markRead(id));
  const markAll = useMutation(async () => notificationsApi.markAllRead());

  const items = list.data?.items ?? [];

  return (
    <div className="max-w-3xl space-y-gutter">
      <SectionHeading
        title="Notifications"
        subtitle="Campaign decisions, bookings and assignments that concern you."
        action={
          <div className="flex flex-wrap items-center gap-space-sm">
            <Button
              variant={unreadOnly ? 'primary' : 'secondary'}
              onClick={() => setUnreadOnly(!unreadOnly)}
            >
              {unreadOnly ? 'Showing unread' : 'All'}
            </Button>
            <Button
              variant="secondary"
              disabled={markAll.pending || (list.data?.unreadCount ?? 0) === 0}
              onClick={async () => {
                const result = await markAll.run();
                if (result) list.reload();
              }}
            >
              Mark all read
            </Button>
          </div>
        }
      />

      {markAll.error || markRead.error ? (
        <Callout tone="error">
          {(markAll.error ?? markRead.error)?.message}
        </Callout>
      ) : null}

      {list.error ? (
        <ErrorState
          message={list.error.message}
          isNetworkError={list.error.isNetworkError}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <LoadingState label="Loading notifications…" />
      ) : items.length === 0 ? (
        <EmptyState
          title={unreadOnly ? 'Nothing unread' : 'No notifications yet'}
          hint={
            unreadOnly
              ? 'You are all caught up.'
              : 'Decisions on your campaigns will appear here.'
          }
        />
      ) : (
        <ul className="space-y-space-sm">
          {items.map((item) => (
            <Card
              as="li"
              key={item.id}
              className={cn('p-gutter', !item.read && 'border-primary/40 bg-primary-container/10')}
            >
              <div className="flex flex-wrap items-start justify-between gap-space-sm">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-space-sm">
                    <p className="font-label-md text-label-md text-on-surface">{item.title}</p>
                    {!item.read ? (
                      <Badge className="border-primary bg-primary text-on-primary">New</Badge>
                    ) : null}
                  </div>
                  {item.body ? (
                    <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">
                      {item.body}
                    </p>
                  ) : null}
                  <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">
                    {item.type.replace(/_/g, ' ').toLowerCase()} ·{' '}
                    {formatRelative(item.createdAt)}
                  </p>
                </div>

                {!item.read ? (
                  <Button
                    variant="secondary"
                    disabled={markRead.pending}
                    onClick={async () => {
                      const result = await markRead.run(item.id);
                      if (result) list.reload();
                    }}
                  >
                    Mark read
                  </Button>
                ) : null}
              </div>
            </Card>
          ))}
        </ul>
      )}
    </div>
  );
}
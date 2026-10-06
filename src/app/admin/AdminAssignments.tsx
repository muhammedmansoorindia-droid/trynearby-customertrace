/**
 * Administrator view of creator-to-restaurant assignments.
 *
 * <p>This is the only place an administrator can hand a creator access to a restaurant, and it is
 * what makes the venue appear in that creator's New Campaign wizard. The alternative — each
 * restaurant self-assigning from its own portal — leaves a creator who has not onboarded yet with
 * nowhere to run a campaign, which is the gap this screen closes.
 *
 * <p>Every rule here is the server's: it refuses a duplicate pair, refuses non-admins, and refuses
 * unknown ids. The screen only chooses pairs and reports what came back.
 */

import { useCallback, useMemo, useState } from 'react';

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
import { assignmentsApi } from '@/services/backend/community';
import type { Assignment, CreatorSummary, RestaurantSummary } from '@/services/backend/community';
import { formatRelative } from '@/utils/format';

export function AdminAssignments() {
  const [creatorId, setCreatorId] = useState('');
  const [restaurantId, setRestaurantId] = useState('');
  const [notice, setNotice] = useState<string | null>(null);

  const list = useResource(useCallback(() => assignmentsApi.listAll(), []), []);
  const creators = useResource(useCallback(() => assignmentsApi.creatorDirectory(), []), []);
  const restaurants = useResource(useCallback(() => assignmentsApi.restaurantDirectory(), []), []);

  const assign = useMutation(async (rId: number, cId: number) =>
    assignmentsApi.assignAsAdmin(rId, cId),
  );
  const deactivate = useMutation(async (id: number) => assignmentsApi.deactivateAsAdmin(id));
  const reactivate = useMutation(async (id: number) => assignmentsApi.reactivate(id));

  const assignments = list.data ?? [];
  const active = useMemo(() => assignments.filter((a) => a.active), [assignments]);
  const ended = useMemo(() => assignments.filter((a) => !a.active), [assignments]);

  // The pair already in force is disabled in the pickers, so a duplicate cannot even be composed.
  // The server rejects it regardless; this just avoids offering the mistake.
  const takenPairs = useMemo(
    () => new Set(active.map((a) => `${a.restaurantId}:${a.creatorId}`)),
    [active],
  );
  const pairAlreadyActive =
    creatorId !== '' && restaurantId !== '' && takenPairs.has(`${restaurantId}:${creatorId}`);

  const pickersLoading = creators.loading || restaurants.loading;
  const pickersFailed = creators.error ?? restaurants.error;
  const mutationError = assign.error ?? deactivate.error ?? reactivate.error;

  const submit = async () => {
    setNotice(null);
    if (!creatorId || !restaurantId) return;
    const created = await assign.run(Number(restaurantId), Number(creatorId));
    if (created) {
      // Cleared so the same pair is not re-submitted straight away.
      setCreatorId('');
      setRestaurantId('');
      setNotice(
        `${created.creatorName ?? 'Creator'} can now run campaigns for ` +
          `${created.restaurantName ?? 'the restaurant'}.`,
      );
      list.reload();
    }
  };

  const endAssignment = async (id: number) => {
    setNotice(null);
    const result = await deactivate.run(id);
    if (result) {
      setNotice('Assignment ended. No new campaigns can be created; existing ones are untouched.');
      list.reload();
    }
  };

  const restoreAssignment = async (id: number) => {
    setNotice(null);
    const result = await reactivate.run(id);
    if (result) {
      setNotice('Assignment restored. The restaurant is available in the creator wizard again.');
      list.reload();
    }
  };
return (
    <div className="max-w-3xl space-y-gutter">
      <SectionHeading
        title="Creator assignments"
        subtitle="Decide which creators may run campaigns for which restaurants."
        action={
          <Button variant="secondary" onClick={list.reload}>
            Refresh
          </Button>
        }
      />

      <Card className="p-gutter">
        <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
          Assign a restaurant to a creator
        </h3>

        {notice ? <Callout tone="success">{notice}</Callout> : null}
        {mutationError ? <Callout tone="error">{mutationError.message}</Callout> : null}

        {pickersLoading ? (
          <LoadingState label="Loading creators and restaurants…" />
        ) : pickersFailed ? (
          <ErrorState
            message={pickersFailed.message}
            isNetworkError={pickersFailed.isNetworkError}
            onRetry={() => {
              creators.reload();
              restaurants.reload();
            }}
          />
        ) : creators.data?.length === 0 || restaurants.data?.length === 0 ? (
          <EmptyState
            title="Nothing to assign yet"
            hint="A creator or restaurant profile has to exist before the two can be linked."
          />
        ) : (
          <>
            <div className="grid gap-space-md sm:grid-cols-2">
              <Field label="Creator">
                <select
                  className={inputClass}
                  value={creatorId}
                  aria-label="Creator"
                  onChange={(e) => setCreatorId(e.target.value)}
                >
                  <option value="">Select a creator…</option>
                  {(creators.data ?? []).map((c) => (
                    <option key={c.id} value={c.id}>
                      {creatorOptionLabel(c)}
                    </option>
                  ))}
                </select>
              </Field>

              <Field label="Restaurant">
                <select
                  className={inputClass}
                  value={restaurantId}
                  aria-label="Restaurant"
                  onChange={(e) => setRestaurantId(e.target.value)}
                >
                  <option value="">Select a restaurant…</option>
                  {(restaurants.data ?? []).map((r) => (
                    <option key={r.id} value={r.id}>
                      {restaurantOptionLabel(r)}
                    </option>
                  ))}
                </select>
              </Field>
            </div>

            {pairAlreadyActive ? (
              <p className="mt-space-sm text-body-sm text-body-sm text-on-surface-variant">
                That pair is already active. Reactivate it below instead.
              </p>
            ) : null}

            <div className="mt-gutter flex justify-end">
              <Button
                onClick={submit}
                disabled={!creatorId || !restaurantId || pairAlreadyActive || assign.pending}
              >
                {assign.pending ? 'Assigning…' : 'Assign restaurant'}
              </Button>
            </div>
          </>
        )}
      </Card>

      <section aria-label="Active assignments" className="space-y-space-sm">
        <h3 className="font-headline-sm text-headline-sm text-on-surface">
          Active ({active.length})
        </h3>
        {list.error ? (
          <ErrorState
            message={list.error.message}
            isNetworkError={list.error.isNetworkError}
            onRetry={list.reload}
          />
        ) : list.loading ? (
          <LoadingState label="Loading assignments…" />
        ) : active.length === 0 ? (
          <EmptyState
            title="No active assignments"
            hint="Use the form above to give a creator a venue to run campaigns for."
          />
        ) : (
          <ul className="space-y-space-sm">
            {active.map((assignment) => (
              <AssignmentRow
                key={assignment.id}
                assignment={assignment}
                actionLabel="Deactivate"
                pending={deactivate.pending}
                onAction={() => endAssignment(assignment.id)}
              />
            ))}
          </ul>
        )}
      </section>

      <section aria-label="Inactive assignments" className="space-y-space-sm">
        <h3 className="font-headline-sm text-headline-sm text-on-surface">
          Inactive ({ended.length})
        </h3>
        {list.loading ? null : ended.length === 0 ? (
          <EmptyState title="Nothing has been deactivated" hint="Ended assignments appear here." />
        ) : (
          <ul className="space-y-space-sm">
            {ended.map((assignment) => (
              <AssignmentRow
                key={assignment.id}
                assignment={assignment}
                actionLabel="Reactivate"
                pending={reactivate.pending}
                onAction={() => restoreAssignment(assignment.id)}
              />
            ))}
          </ul>
        )}
      </section>

      <p className="text-body-sm text-body-sm text-on-surface-variant">
        Deactivating stops new campaigns for that restaurant. Campaigns already created are left
        exactly as they are, along with their scans and redemptions.
      </p>
    </div>
  );
}

/** One assignment line, shared by the active and inactive lists. */
function AssignmentRow({
  assignment,
  actionLabel,
  pending,
  onAction,
}: {
  assignment: Assignment;
  actionLabel: string;
  pending: boolean;
  onAction: () => void;
}) {
  return (
    <Card as="li" className="p-gutter">
      <div className="flex flex-wrap items-center justify-between gap-space-sm">
        <div>
          <p className="font-label-md text-label-md text-on-surface">
            {assignment.creatorName ?? `Creator #${assignment.creatorId}`} →{' '}
            {assignment.restaurantName ?? `Restaurant #${assignment.restaurantId}`}
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
            {assignment.active ? 'Active' : 'Inactive'}
          </Badge>
          <Button variant="secondary" disabled={pending} onClick={onAction}>
            {actionLabel}
          </Button>
        </div>
      </div>
    </Card>
  );
}

/** `Name — subtitle`, or just the name when there is nothing to disambiguate with. */
/**
 * Picker labels.
 *
 * <p>The directory endpoints now return the full summary records, so the label is built from the
 * fields each one actually carries. An inactive profile is marked inline so it cannot be picked by
 * accident — the backend would accept it, but the mistake would surface much later.
 */
function creatorOptionLabel(creator: CreatorSummary): string {
  const detail = [creator.handle, creator.city].filter(Boolean).join(' · ');
  return creator.status === 'ACTIVE'
    ? `${creator.displayName} — ${detail}`
    : `${creator.displayName} — ${detail} · inactive`;
}

function restaurantOptionLabel(restaurant: RestaurantSummary): string {
  const detail = restaurant.city;
  return restaurant.status === 'ACTIVE'
    ? `${restaurant.name} — ${detail}`
    : `${restaurant.name} — ${detail} · inactive`;
}
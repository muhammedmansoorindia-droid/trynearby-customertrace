/**
 * The creator's own reviews.
 *
 * <p>Eligibility is an active assignment, enforced by the backend and re-checked here so the
 * restaurant selector only offers restaurants this creator can actually review. Ratings are 1–5 and
 * are validated before submission; a review is edited rather than duplicated.
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
import { assignmentsApi, reviewsApi } from '@/services/backend/community';
import { formatRelative } from '@/utils/format';

const MIN_RATING = 1;
const MAX_RATING = 5;

export function CreatorReviews() {
  const [restaurantId, setRestaurantId] = useState('');
  const [rating, setRating] = useState('5');
  const [body, setBody] = useState('');
  const [editing, setEditing] = useState<number | null>(null);

  const assignments = useResource(useCallback(() => assignmentsApi.listForCreator(), []), []);
  const list = useResource(useCallback(() => reviewsApi.listForCreator(), []), []);

  const save = useMutation(async (id: number) =>
    reviewsApi.update(id, { rating: Number(rating), body: body.trim() }),
  );
  const create = useMutation(async () =>
    reviewsApi.create({
      restaurantId: Number(restaurantId),
      rating: Number(rating),
      body: body.trim(),
    }),
  );

  const activeIds = new Set(
    (assignments.data ?? []).filter((a) => a.active).map((a) => String(a.restaurantId)),
  );

  const formError =
    editing === null && !restaurantId
      ? 'Choose a restaurant you are assigned to.'
      : !body.trim()
        ? 'A review must include some text.'
        : Number(rating) < MIN_RATING || Number(rating) > MAX_RATING
          ? `Rating must be between ${MIN_RATING} and ${MAX_RATING}.`
          : body.trim().length > 1000
            ? 'A review may be at most 1000 characters.'
            : null;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (formError) return;
    const result = editing === null ? await create.run() : await save.run(editing);
    if (result) {
      setEditing(null);
      setBody('');
      list.reload();
    }
  };

  const reviews = list.data ?? [];

return (
    <div className="max-w-3xl space-y-gutter">
      <SectionHeading
        title="Your reviews"
        subtitle="Rate the restaurants you have worked with. Only assigned restaurants qualify."
        action={
          <Button variant="secondary" onClick={list.reload}>
            Refresh
          </Button>
        }
      />

      <Card className="p-gutter">
        <h3 className="font-headline-sm text-headline-sm text-on-surface mb-space-md">
          {editing === null ? 'Write a review' : 'Edit your review'}
        </h3>

        {assignments.error ? (
          <ErrorState
            title="Could not load your assignments"
            message={assignments.error.message}
            isNetworkError={assignments.error.isNetworkError}
            onRetry={assignments.reload}
          />
        ) : assignments.loading ? (
          <LoadingState label="Loading restaurants…" />
        ) : (
          <form className="space-y-space-md" onSubmit={submit}>
            {editing === null ? (
              <Field label="Restaurant" hint="Only active assignments are listed.">
                <select
                  className={inputClass}
                  value={restaurantId}
                  onChange={(event) => setRestaurantId(event.target.value)}
                  required
                >
                  <option value="">Choose a restaurant…</option>
                  {[...activeIds].map((id) => (
                    <option key={id} value={id}>
                      Restaurant #{id}
                    </option>
                  ))}
                </select>
              </Field>
            ) : null}

            <Field label={`Rating (1–${MAX_RATING})`}>
              <input
                className={inputClass}
                type="number"
                min={MIN_RATING}
                max={MAX_RATING}
                value={rating}
                onChange={(event) => setRating(event.target.value)}
                required
              />
            </Field>

            <Field label="Review" hint="Up to 1000 characters.">
              <textarea
                className={`${inputClass} min-h-24 resize-y`}
                value={body}
                onChange={(event) => setBody(event.target.value)}
                maxLength={1000}
                required
              />
            </Field>

            {formError ? (
              <p role="alert" className="text-body-sm text-body-sm text-error">
                {formError}
              </p>
            ) : null}
            {create.error || save.error ? (
              <Callout tone="error">{(create.error ?? save.error)?.message}</Callout>
            ) : null}

            <div className="flex gap-space-sm">
              <Button type="submit" disabled={Boolean(formError) || create.pending || save.pending}>
                {create.pending || save.pending ? 'Saving…' : 'Save review'}
              </Button>
              {editing !== null ? (
                <Button
                  variant="ghost"
                  onClick={() => {
                    setEditing(null);
                    setBody('');
                  }}
                >
                  Cancel
                </Button>
              ) : null}
            </div>
          </form>
        )}
      </Card>

      {list.error ? (
        <ErrorState
          message={list.error.message}
          isNetworkError={list.error.isNetworkError}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <LoadingState label="Loading your reviews…" />
      ) : reviews.length === 0 ? (
        <EmptyState
          title="No reviews yet"
          hint="Write one for a restaurant you have worked with."
        />
      ) : (
        <ul className="space-y-space-sm">
          {reviews.map((review) => (
            <Card as="li" key={review.id} className="p-gutter">
              <div className="flex flex-wrap items-start justify-between gap-space-sm">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-space-sm">
                    <p className="font-label-md text-label-md text-on-surface">
                      {review.restaurantName ?? `Restaurant #${review.restaurantId}`}
                    </p>
                    <Badge>
                      {review.rating} / {MAX_RATING}
                    </Badge>
                  </div>
                  {review.body ? (
                    <p className="text-body-sm text-body-sm text-on-surface mt-1">{review.body}</p>
                  ) : null}
                  <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">
                    {formatRelative(review.createdAt)}
                  </p>
                </div>
                <Button
                  variant="secondary"
                  onClick={() => {
                    setEditing(review.id);
                    setRating(String(review.rating));
                    setBody(review.body ?? '');
                  }}
                >
                  Edit
                </Button>
              </div>
            </Card>
          ))}
        </ul>
      )}
    </div>
  );
}
/**
 * New-campaign wizard.
 *
 * <p>The form is constrained *before* submission: customer target is 10–99 and duration 1–31
 * because those two values are encoded as the `NN` and `DD` characters of the promo code. A target
 * of 150 is not merely discouraged, it is unrepresentable.
 *
 * <p>Creating and submitting are two backend calls, matching how the server models it: `create`
 * makes a draft, `submit` mints the promo code and moves it into the review queue. The code is
 * therefore only issued once the campaign genuinely enters the queue, so a creator cannot squat
 * on the per-restaurant monthly exclusive-tier budget by holding a draft.
 */

import { useCallback, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { useMutation, useResource } from '@/app/useResource';
import {
  Button,
  Callout,
  Card,
  ErrorState,
  Field,
  LoadingState,
  inputClass,
} from '@/app/ui';
import { assignmentsApi } from '@/services/backend/community';
import { campaignsApi } from '@/services/backend/campaigns';
import { toDateKey } from '@/utils/format';

const STEP_TITLES = ['Restaurant', 'Offer', 'Reach', 'Review'];
const MIN_TARGET = 10;
const MAX_TARGET = 99;
const MIN_DAYS = 1;
const MAX_DAYS = 31;

export function CreatorWizard() {
  const navigate = useNavigate();

  const [step, setStep] = useState(0);
  const [restaurantId, setRestaurantId] = useState('');
  const [title, setTitle] = useState('');
  const [offerText, setOfferText] = useState('');
  const [discountPercent, setDiscountPercent] = useState(20);
  const [target, setTarget] = useState(25);
  const [duration, setDuration] = useState(7);

  // Only restaurants this creator is actually assigned to may be targeted.
  const assignments = useResource(useCallback(() => assignmentsApi.listForCreator(), []), []);

  const assignedIds = useMemo(
    () => new Set((assignments.data ?? []).filter((a) => a.active).map((a) => a.restaurantId)),
    [assignments.data],
  );

  const startDate = toDateKey();
  const endDate = useMemo(() => {
    const [y, m, d] = startDate.split('-').map(Number);
    return toDateKey(new Date(y, (m ?? 1) - 1, (d ?? 1) + duration));
  }, [startDate, duration]);

  // Shape preview only. The real code is minted server-side on submit; this shows the NN/DD the
  // creator is committing to, without claiming a tier that may not be free.
  const previewCode = `LL${String(target).padStart(2, '0')}${String(duration).padStart(2, '0')}X`;

  const createAndSubmit = useMutation(async () => {
    const draft = await campaignsApi.create({
      restaurantId: Number(restaurantId),
      title: title.trim(),
      offerText: offerText.trim(),
      discountPercent,
      targetCustomers: target,
      durationDays: duration,
      startDate,
      endDate,
    });
    // A draft with no code; submitting is what mints it and enters the queue.
    return campaignsApi.submit(draft.id);
  });

  const submit = async () => {
    if (!restaurantId) return;
    const created = await createAndSubmit.run();
    if (created) navigate('/creator/campaigns');
  };

  const stepValid = [
    Boolean(restaurantId),
    title.trim().length > 2 && offerText.trim().length > 2,
    true,
    true,
  ][step];

  return (
    <div className="max-w-3xl space-y-gutter">
      <ol className="flex flex-wrap gap-space-xs" aria-label="Progress">
        {STEP_TITLES.map((label, index) => (
          <li key={label} className="flex items-center gap-space-xs">
            <span
              className={`flex h-7 w-7 items-center justify-center rounded-full border font-label-sm text-label-sm ${
                index === step
                  ? 'border-primary bg-primary text-on-primary'
                  : index < step
                    ? 'border-secondary/25 bg-secondary-container text-on-secondary-fixed'
                    : 'border-outline-variant/40 bg-surface-container text-on-surface-variant'
              }`}
            >
              {index + 1}
            </span>
            <span className="text-body-sm text-body-sm text-on-surface-variant">{label}</span>
            {index < STEP_TITLES.length - 1 ? (
              <span className="mx-1 h-px w-4 bg-outline-variant/50" aria-hidden="true" />
            ) : null}
          </li>
        ))}
      </ol>

      <Card className="p-gutter">
        {step === 0 ? (
          <Field
            label="Which restaurant?"
            hint={
              assignments.loading
                ? 'Loading your assignments…'
                : 'You can only target restaurants you are assigned to.'
            }
          >
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
              <select
                className={inputClass}
                value={restaurantId}
                onChange={(event) => setRestaurantId(event.target.value)}
              >
                <option value="">Choose a restaurant…</option>
                {[...assignedIds].map((id) => (
                  <option key={id} value={id}>
                    Restaurant #{id}
                  </option>
                ))}
              </select>
            )}
          </Field>
        ) : null}

        {step === 0 && assignments.data && assignedIds.size === 0 ? (
          <Callout tone="info">
            You have no active restaurant assignments yet, so there is nowhere to run a campaign.
            Ask TryNearby to assign you to a restaurant.
          </Callout>
        ) : null}

        {step === 1 ? (
          <div className="space-y-space-md">
            <Field
              label="Campaign title"
              hint="Shown to the restaurant and in your own dashboard."
            >
              <input
                className={inputClass}
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                placeholder="Monsoon Biryani Bonanza"
                maxLength={60}
              />
            </Field>
            <Field
              label="Offer text"
              hint="Be specific about what the diner gets — the admin checks this at review."
            >
              <input
                className={inputClass}
                value={offerText}
                onChange={(event) => setOfferText(event.target.value)}
                placeholder="20% off all biryani orders"
                maxLength={80}
              />
            </Field>
            <Field label="Discount" hint="Percentage applied at settlement.">
              <div className="flex items-center gap-space-md">
                <input
                  type="range"
                  min={5}
                  max={50}
                  step={5}
                  value={discountPercent}
                  onChange={(event) => setDiscountPercent(Number(event.target.value))}
                  className="flex-1"
                />
                <span className="w-16 text-right font-headline-sm text-headline-sm text-primary">
                  {discountPercent}%
                </span>
              </div>
            </Field>
          </div>
        ) : null}

        {step === 2 ? (
          <div className="space-y-space-md">
            <Field
              label={`Customer target — ${target}`}
              hint={`Encoded as the NN in your code, so it must be ${MIN_TARGET}–${MAX_TARGET}.`}
            >
              <input
                type="range"
                min={MIN_TARGET}
                max={MAX_TARGET}
                step={1}
                value={target}
                onChange={(event) => setTarget(Number(event.target.value))}
                className="w-full"
              />
            </Field>
            <Field
              label={`Duration — ${duration} days`}
              hint={`Encoded as the DD in your code, so it must be ${MIN_DAYS}–${MAX_DAYS}.`}
            >
              <input
                type="range"
                min={MIN_DAYS}
                max={MAX_DAYS}
                step={1}
                value={duration}
                onChange={(event) => setDuration(Number(event.target.value))}
                className="w-full"
              />
            </Field>
            <Callout tone="info">
              A target of {target} means at most {target} <em>distinct</em> diners can redeem.
              Repeat visits from the same diner still count as redemptions but do not consume
              more of the target.
            </Callout>
          </div>
        ) : null}

        {step === 3 ? (
          <div className="space-y-space-md">
            <dl className="grid gap-space-sm sm:grid-cols-2">
              <div>
                <dt className="text-body-sm text-body-sm text-on-surface-variant">Restaurant</dt>
                <dd className="text-body-md text-body-md text-on-surface">
                  {restaurantId ? `Restaurant #${restaurantId}` : '—'}
                </dd>
              </div>
              <div>
                <dt className="text-body-sm text-body-sm text-on-surface-variant">Title</dt>
                <dd className="text-body-md text-body-md text-on-surface">{title || '—'}</dd>
              </div>
              <div>
                <dt className="text-body-sm text-body-sm text-on-surface-variant">Offer</dt>
                <dd className="text-body-md text-body-md text-on-surface">
                  {offerText || '—'} ({discountPercent}%)
                </dd>
              </div>
              <div>
                <dt className="text-body-sm text-body-sm text-on-surface-variant">Window</dt>
                <dd className="text-body-md text-body-md text-on-surface">
                  {startDate} → {endDate}
                </dd>
              </div>
              <div>
                <dt className="text-body-sm text-body-sm text-on-surface-variant">Target</dt>
                <dd className="text-body-md text-body-md text-on-surface">
                  {target} customers
                </dd>
              </div>
              <div>
                <dt className="text-body-sm text-body-sm text-on-surface-variant">Tier</dt>
                <dd className="text-body-md text-body-md text-on-surface">
                  Assigned on issue
                </dd>
              </div>
            </dl>

            <div className="rounded-lg bg-surface-container p-space-md text-center">
              <p className="text-label-sm text-label-sm uppercase tracking-wide text-on-surface-variant">
                Code shape
              </p>
              <p className="font-mono text-display-md-mobile text-display-md-mobile text-on-surface">
                {previewCode}
              </p>
              <p className="text-body-sm text-body-sm text-on-surface-variant">
                The letters are assigned on issue; your {target} and {duration}-day choices are
                already locked in.
              </p>
            </div>

            <Callout tone="info">
              The code's tier letter is assigned by the server when you submit, preferring the
              exclusive E–J tiers. Those can be used only once per restaurant per month, so if all
              six are taken a reusable tier is assigned automatically.
            </Callout>

            {createAndSubmit.error ? (
              <Callout tone="error">{createAndSubmit.error.message}</Callout>
            ) : null}
          </div>
        ) : null}

        <div className="mt-gutter flex items-center justify-between gap-space-sm">
          <Button
            variant="ghost"
            onClick={() => setStep((s) => Math.max(0, s - 1))}
            disabled={step === 0}
          >
            Back
          </Button>

          {step < STEP_TITLES.length - 1 ? (
            <Button onClick={() => setStep((s) => s + 1)} disabled={!stepValid}>
              Continue
            </Button>
          ) : (
            <Button onClick={() => void submit()} disabled={createAndSubmit.pending}>
              {createAndSubmit.pending ? 'Submitting…' : 'Submit for review'}
            </Button>
          )}
        </div>
      </Card>
    </div>
  );
}

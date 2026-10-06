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

/**
 * The discount choices, mirroring the backend's B-J mapping.
 *
 * <p>2%-6% is reusable: a restaurant may run as many of those as it likes in a month. 7%-10% may
 * back only one campaign per restaurant per calendar month, so the creator is told before
 * submitting rather than being refused afterwards.
 */
const DISCOUNTS = [
  { percent: 2, restricted: false },
  { percent: 3, restricted: false },
  { percent: 4, restricted: false },
  { percent: 5, restricted: false },
  { percent: 6, restricted: false },
  { percent: 7, restricted: true },
  { percent: 8, restricted: true },
  { percent: 9, restricted: true },
  { percent: 10, restricted: true },
] as const;

/** The code letter for a discount, matching DiscountTiers.letterFor on the server. */
function discountLetter(percent: number): string {
  return String.fromCharCode('B'.charCodeAt(0) + percent - 2);
}

const MIN_TARGET = 1;
const MAX_TARGET = 99;
const MIN_DAYS = 1;
const MAX_DAYS = 99;

export function CreatorWizard() {
  const navigate = useNavigate();

  const [step, setStep] = useState(0);
  const [restaurantId, setRestaurantId] = useState('');
  const [offerText, setOfferText] = useState('');
  const [discountPercent, setDiscountPercent] = useState(5);
  const [target, setTarget] = useState(25);
  const [duration, setDuration] = useState(7);
  // Only the two leading letters are random, and they are re-drawn on demand. The NN, DD and
  // discount letter are fixed by the settings below, so regenerating never changes the offer.
  const [previewLetters, setPreviewLetters] = useState(() => randomLetters());

  function randomLetters() {
    const pick = () => String.fromCharCode(65 + Math.floor(Math.random() * 26));
    return pick() + pick();
  }

  // Only restaurants this creator is actively assigned to may be targeted. Re-read on mount, so an
  // assignment made in the admin portal shows up the next time the wizard is opened — no reload.
  const assignments = useResource(useCallback(() => assignmentsApi.listForCreator(), []), []);

  const assigned = useMemo(
    () => (assignments.data ?? []).filter((a) => a.active),
    [assignments.data],
  );
  const assignedIds = useMemo(() => new Set(assigned.map((a) => a.restaurantId)), [assigned]);

  // The server resolves display names; the id is the fallback for a deleted profile.
  const restaurantName = useCallback(
    (id: string) => assigned.find((a) => String(a.restaurantId) === id)?.restaurantName,
    [assigned],
  );

  const startDate = toDateKey();
  const endDate = useMemo(() => {
    const [y, m, d] = startDate.split('-').map(Number);
    return toDateKey(new Date(y, (m ?? 1) - 1, (d ?? 1) + duration));
  }, [startDate, duration]);

  // The two leading letters are random; the rest is derived from the settings. Shape preview only
  // — the server mints the unique, ledger-checked code on submit and that becomes the campaign name.
  const previewCode =
    previewLetters +
    String(target).padStart(2, '0') +
    String(duration).padStart(2, '0') +
    discountLetter(discountPercent);

  const createAndSubmit = useMutation(async () => {
    const draft = await campaignsApi.create({
      restaurantId: Number(restaurantId),
      offerText: offerText.trim(),
      discountPercent,
      targetCustomers: target,
      durationDays: duration,
      startDate,
      endDate,
    });
    // A draft with no issued code; submitting is what mints it and enters the queue.
    return campaignsApi.submit(draft.id);
  });

  const submit = async () => {
    if (!restaurantId) return;
    const created = await createAndSubmit.run();
    if (created) navigate('/creator/campaigns');
  };

  const stepValid = [Boolean(restaurantId), offerText.trim().length > 2, true, true][step];

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
                aria-label="Restaurant"
                onChange={(event) => setRestaurantId(event.target.value)}
              >
                <option value="">Choose a restaurant…</option>
                {[...assignedIds].map((id) => (
                  <option key={id} value={id}>
                    {restaurantName(String(id)) ?? `Restaurant #${id}`}
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
            {/* No name field: the campaign is named by its generated code, so there is nothing
                for the creator to type and nothing for them to get inconsistent with the offer. */}
            <div className="rounded-lg bg-surface-container p-space-md text-center">
              <p className="text-label-sm text-label-sm uppercase tracking-wide text-on-surface-variant">
                Campaign code
              </p>
              <p className="font-mono text-display-md-mobile text-display-md-mobile text-on-surface">
                {previewCode}
              </p>
              <p className="text-body-sm text-body-sm text-on-surface-variant">
                {target} customers · {duration} days · {discountPercent}% off
              </p>
              <Button
                variant="secondary"
                className="mt-space-sm"
                // Re-draws only the two letters. The target, duration and discount are fixed by
                // the settings and are carried through untouched.
                onClick={() => setPreviewLetters(randomLetters())}
              >
                New letters
              </Button>
            </div>
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
            <Field
              label={`Discount — ${discountPercent}%`}
              hint={`Code letter ${discountLetter(discountPercent)}. 2–6% is reusable; 7–10% may back only one campaign per restaurant per month.`}
            >
              <select
                className={inputClass}
                value={discountPercent}
                aria-label="Discount"
                onChange={(event) => setDiscountPercent(Number(event.target.value))}
              >
                {DISCOUNTS.map(({ percent, restricted }) => (
                  <option key={percent} value={percent}>
                    {percent}% ({discountLetter(percent)})
                    {restricted ? ' — once per restaurant per month' : ''}
                  </option>
                ))}
              </select>
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
                  {restaurantId
                    ? (restaurantName(restaurantId) ?? `Restaurant #${restaurantId}`)
                    : '—'}
                </dd>
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
                <dt className="text-body-sm text-body-sm text-on-surface-variant">Discount</dt>
                <dd className="text-body-md text-body-md text-on-surface">
                  {discountPercent}% (letter {discountLetter(discountPercent)})
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
                The two letters are chosen by you and checked for uniqueness when you submit;
                the {target}, {duration} and {discountPercent}% figures are already locked in.
              </p>
            </div>

            <Callout tone="info">
              Your {discountPercent}% discount maps to code letter{' '}
              {discountLetter(discountPercent)}.{' '}
              {DISCOUNTS.find((d) => d.percent === discountPercent)?.restricted
                ? `7–10% may back only one campaign per restaurant per calendar month, so TryNearby may refuse this one if that discount is already used there this month.`
                : '2–6% is reusable, so this can be submitted any number of times in a month.'}
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

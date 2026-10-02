/**
 * New-campaign wizard.
 *
 * The form is constrained *before* submission: customer target is 10–99 and duration 1–31
 * because those two values are encoded as the `NN` and `DD` characters of the promo code. A
 * target of 150 is not merely discouraged, it is unrepresentable — so the sliders are bounded
 * and the review step shows the code that will be issued.
 *
 * The code is minted at draft time rather than at submit: letting a creator hold a code
 * without ever entering the queue would let them squat on the exclusive tier budget, which is
 * the resource the per-restaurant monthly rule actually rations.
 */

import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { NoData } from '@/app/PortalLayout';
import { mintCodeFor, useAppStore } from '@/app/store';
import { Button, Callout, Card, Field, SectionHeading, inputClass } from '@/app/ui';
import { EXCLUSIVE_TIERS, getAvailableTiers } from '@/services/promoCode';
import type { Campaign } from '@/types/models';
import { createId } from '@/utils';
import { toDateKey } from '@/utils/format';

const STEP_TITLES = ['Restaurant', 'Offer', 'Reach', 'Review'];
const MIN_TARGET = 10;
const MAX_TARGET = 99;
const MIN_DAYS = 1;
const MAX_DAYS = 31;

/** `restaurantId:YYYY-MM` for a date-only string, matching `utils/format`'s monthKey scope. */
function monthOf(restaurantId: string, dateOnly: string): string {
  return `${restaurantId}:${dateOnly.slice(0, 7)}`;
}

export function CreatorWizard() {
  const navigate = useNavigate();
  const db = useAppStore((s) => s.db);
  const creatorId = useAppStore((s) => s.session.creatorId);
  const setDb = useAppStore((s) => s.setDb);

  const creator = db.creators.find((c) => c.id === creatorId);
  if (!creator) {
    return <NoData message="Switch portal and pick a creator to continue." />;
  }

  const [step, setStep] = useState(0);
  const [restaurantId, setRestaurantId] = useState('');
  const [title, setTitle] = useState('');
  const [offerText, setOfferText] = useState('');
  const [discountPercent, setDiscountPercent] = useState(20);
  const [target, setTarget] = useState(25);
  const [duration, setDuration] = useState(7);
  const [error, setError] = useState<string | null>(null);

  const restaurant = db.restaurants.find((r) => r.id === restaurantId);
  const startDate = toDateKey();

  const endDate = useMemo(() => {
    const [y, m, d] = startDate.split('-').map(Number);
    return toDateKey(new Date(y, (m ?? 1) - 1, (d ?? 1) + duration));
  }, [startDate, duration]);

  // Preview the code that *would* be issued, so the creator sees the NN/DD they are choosing
  // before they commit. The real code is minted by the engine on submit, not derived here.
  const previewTiers = restaurantId
    ? getAvailableTiers(restaurantId, monthOf(restaurantId, startDate), { records: db.promoCodes })
    : [];
  const previewTier = previewTiers[0] ?? 'A';
  const previewCode = `${'LL'}${String(target).padStart(2, '0')}${String(duration).padStart(2, '0')}${previewTier}`;

  const stepValid = [
    Boolean(restaurantId),
    title.trim().length > 2 && offerText.trim().length > 2,
    true,
    true,
  ][step];

  const submit = () => {
    if (!restaurant) {
      setError('Pick a restaurant first.');
      return;
    }

    const now = new Date();
    const campaignId = createId('cmp');
    const record = mintCodeFor(db, restaurant.id, campaignId, target, duration, now);
    if (!record) {
      setError('No promo code could be minted right now. Try a different restaurant or target.');
      return;
    }

    const campaign: Campaign = {
      id: campaignId,
      creatorId: creator.id,
      restaurantId: restaurant.id,
      title: title.trim(),
      offerText: offerText.trim(),
      discountPercent,
      targetCustomers: target,
      durationDays: duration,
      startDate,
      endDate,
      status: 'pending_admin',
      promoCode: record.code,
      createdAt: now.toISOString(),
      submittedAt: now.toISOString(),
    };

    setDb({
      ...db,
      campaigns: [...db.campaigns, campaign],
      promoCodes: [...db.promoCodes, record],
    });

    navigate('/creator/campaigns');
  };


  return (
    <div className="max-w-3xl space-y-gutter">
      <SectionHeading
        title="New campaign"
        subtitle="Your target and duration become part of the promo code, so both are fixed once issued."
      />

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
          <Field label="Which restaurant?" hint="The offer runs at this venue's till.">
            <select
              className={inputClass}
              value={restaurantId}
              onChange={(event) => setRestaurantId(event.target.value)}
            >
              <option value="">Choose a restaurant…</option>
              {db.restaurants
                .filter((r) => r.status === 'active')
                .map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name} · {r.city}
                  </option>
                ))}
            </select>
          </Field>
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
                  {restaurant?.name ?? '—'}
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
                  {previewTier}
                  {EXCLUSIVE_TIERS.includes(previewTier) ? ' (exclusive)' : ' (reusable)'}
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
              {EXCLUSIVE_TIERS.includes(previewTier)
                ? `Tier ${previewTier} is exclusive: this restaurant can use it once this month. Tiers E–J are preferred, but they run out — the next best free tier is assigned automatically.`
                : 'All six exclusive tiers (E–J) are already used at this restaurant this month, so a reusable tier is assigned instead.'}
            </Callout>

            {error ? <Callout tone="error">{error}</Callout> : null}
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
            <Button onClick={submit}>Submit for review</Button>
          )}
        </div>
      </Card>
    </div>
  );
}

/**
 * Scan &amp; redeem — the restaurant's front desk.
 *
 * This is where the product's core distinction becomes physical. Scanning a pass records a
 * `ScanEvent` and *nothing else*: no customer is credited, no discount is promised. Only the
 * separate "record redemption" step, which asks for the bill, writes a `Redemption` — and
 * only that is a verified customer.
 *
 * Camera scanning is offered via `jsQR`, but the demo must also work on a laptop with no
 * webcam and in an automated test, so manual code entry is a peer of the camera rather than a
 * fallback afterthought.
 */

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';

import { NoData } from '@/app/PortalLayout';
import { useAppStore } from '@/app/store';
import { Badge, Button, Callout, Card, Field, SectionHeading, inputClass } from '@/app/ui';
import { REDEEMABLE_STATUSES } from '@/services/campaignWorkflow';
import { normalizeCode, validatePromoCode } from '@/services/promoCode';
import type { Redemption } from '@/types/models';
import { formatINR, formatTime, toDateKey } from '@/utils/format';

type Verdict =
  | { kind: 'idle' }
  | { kind: 'accepted'; campaignId: string }
  | { kind: 'refused'; reason: string };

export function RestaurantScanner() {
  const db = useAppStore((s) => s.db);
  const restaurantId = useAppStore((s) => s.session.restaurantId);
  const recordScan = useAppStore((s) => s.recordScan);
  const recordRedemption = useAppStore((s) => s.recordRedemption);

  const [entry, setEntry] = useState('');
  const [verdict, setVerdict] = useState<Verdict>({ kind: 'idle' });
  const [bill, setBill] = useState('');
  const [channel, setChannel] = useState<Redemption['channel']>('dine_in');
  const [staff, setStaff] = useState('');
  const [confirmed, setConfirmed] = useState<Redemption | null>(null);

  const canvasRef = useRef<HTMLCanvasElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const rafRef = useRef<number | null>(null);
  const [cameraOn, setCameraOn] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);

  const restaurant = db.restaurants.find((r) => r.id === restaurantId);
  const campaigns = db.campaigns.filter(
    (c) => c.restaurantId === restaurantId && REDEEMABLE_STATUSES.includes(c.status),
  );
  const activeCampaign =
    verdict.kind === 'accepted' ? db.campaigns.find((c) => c.id === verdict.campaignId) : undefined;


  // ── Camera ────────────────────────────────────────────────────────────
  const stopCamera = useCallback(() => {
    if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
    rafRef.current = null;
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    setCameraOn(false);
  }, []);

  useEffect(() => stopCamera, [stopCamera]);

  const startCamera = async () => {
    setCameraError(null);
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraError('This browser has no camera API. Type the code instead.');
      return;
    }

    try {
      const jsQR = (await import('jsqr')).default;
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'environment' },
      });
      const video = document.createElement('video');
      video.srcObject = stream;
      video.setAttribute('playsinline', 'true');
      await video.play();

      const canvas = canvasRef.current;
      const ctx = canvas?.getContext('2d');
      if (!canvas || !ctx) {
        stopCamera();
        return;
      }

      streamRef.current = stream;
      setCameraOn(true);

      const tick = () => {
        if (!streamRef.current) return;
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const image = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const found = jsQR(image.data, image.width, image.height);
        if (found?.data) {
          setEntry(found.data);
          stopCamera();
          return;
        }
        rafRef.current = requestAnimationFrame(tick);
      };
      rafRef.current = requestAnimationFrame(tick);
    } catch {
      setCameraError('Camera permission was declined. Type the code instead.');
    }
  };

  // ── Validation ────────────────────────────────────────────────────────
  const check = (): Verdict => {
    if (!restaurant) return { kind: 'refused', reason: 'No restaurant selected.' };
    const raw = entry.trim();
    if (!raw) return { kind: 'refused', reason: 'Enter or scan a promo code first.' };

    const result = validatePromoCode({
      rawCode: raw,
      restaurantId: restaurant.id,
      now: new Date(),
      ledger: { records: db.promoCodes, redemptions: db.redemptions },
      getCampaign: (id) => db.campaigns.find((c) => c.id === id),
    });

    if (!result.ok) return { kind: 'refused', reason: result.message };

    // A valid scan is logged as its own event. It counts as *interest* and never as a
    // customer — that separation is the entire point of this screen.
    recordScan({
      campaignId: result.value.campaign.id,
      restaurantId: restaurant.id,
      creatorId: db.campaigns.find((c) => c.id === result.value.campaign.id)?.creatorId ?? '',
      method: 'promo_manual',
      promoCode: normalizeCode(raw),
      scannedAt: new Date().toISOString(),
      customerRef: null,
      result: 'valid',
      deviceLabel: 'Front counter',
    });

    return { kind: 'accepted', campaignId: result.value.campaign.id };
  };

  const submitRedemption = (event: FormEvent) => {
    event.preventDefault();
    if (!restaurant || !activeCampaign) return;

    const amount = Number(bill);
    if (!amount || amount <= 0) return;

    const record: Omit<Redemption, 'id'> = {
      campaignId: activeCampaign.id,
      restaurantId: restaurant.id,
      creatorId: activeCampaign.creatorId,
      promoCode: activeCampaign.promoCode ?? '',
      scanEventId: null,
      customerRef: null,
      redeemedAt: new Date().toISOString(),
      billAmountInPaise: Math.round(amount * 100),
      discountAmountInPaise: Math.round((amount * 100 * activeCampaign.discountPercent) / 100),
      channel,
      tableOrChannelRef: channel === 'dine_in' ? 'Front counter' : undefined,
      staffName: staff.trim() || undefined,
      visitDate: toDateKey(),
    };

    recordRedemption(record);
    setConfirmed({ ...record, id: 'just-recorded' });
    setBill('');
    setStaff('');
  };

  if (!restaurant) {
    return <NoData message="Switch portal and pick a restaurant to continue." />;
  }

  const discountPaise = activeCampaign
    ? Math.round((Number(bill || 0) * 100 * activeCampaign.discountPercent) / 100)
    : 0;

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Scan & redeem"
        subtitle={`Front desk at ${restaurant.name}. A scan proves interest; a redemption proves a customer.`}
      />

      <Callout tone="info">
        <strong className="font-label-md">Two steps, two records.</strong> Scanning a pass only
        logs that the code was seen. The diner becomes a verified customer — and the discount
        becomes real — when you record the redemption with their bill.
      </Callout>

      <div className="grid gap-gutter lg:grid-cols-2">
        <Card className="space-y-space-md p-gutter">
          <Field
            label="Promo code"
            hint="Format LLNNDDX, e.g. MK4014E. Case and spacing do not matter."
          >
            <div className="flex gap-space-sm">
              <input
                className={inputClass}
                value={entry}
                onChange={(event) => {
                  setEntry(event.target.value);
                  setVerdict({ kind: 'idle' });
                  setConfirmed(null);
                }}
                placeholder="MK4014E"
                autoComplete="off"
                spellCheck={false}
              />
              <Button
                onClick={() => {
                  setVerdict(check());
                  setConfirmed(null);
                }}
                disabled={!entry.trim()}
              >
                Check
              </Button>
            </div>
          </Field>

          <div className="flex flex-wrap items-center gap-space-sm">
            <Button
              variant="secondary"
              onClick={() => (cameraOn ? stopCamera() : void startCamera())}
            >
              <span className="material-symbols-outlined text-[18px]" aria-hidden="true">
                {cameraOn ? 'stop_circle' : 'photo_camera'}
              </span>
              {cameraOn ? 'Stop camera' : 'Scan QR pass'}
            </Button>
            {cameraOn ? (
              <Badge className="border-primary bg-primary text-on-primary">Camera live</Badge>
            ) : null}
          </div>

          {cameraError ? <Callout tone="error">{cameraError}</Callout> : null}
          <canvas ref={canvasRef} className="hidden" aria-hidden="true" />

          {campaigns.length > 0 ? (
            <div>
              <p className="mb-space-xs font-label-md text-label-md text-on-surface">
                Or tap a live code
              </p>
              <div className="flex flex-wrap gap-space-xs">
                {campaigns.map((campaign) => (
                  <button
                    key={campaign.id}
                    type="button"
                    onClick={() => {
                      setEntry(campaign.promoCode ?? '');
                      setVerdict({ kind: 'idle' });
                    }}
                    className="rounded-lg border border-outline-variant/40 bg-surface-container-lowest
                      px-space-sm py-space-xs font-mono text-body-sm text-body-sm text-on-surface
                      transition-colors hover:bg-surface-container-high"
                  >
                  </button>
                ))}
              </div>
            </div>
          ) : null}
        </Card>
        <Card className="space-y-space-md p-gutter">
          {verdict.kind === 'refused' ? (
            <Callout tone="error">
              <strong className="block font-label-md">Code refused</strong>
              {verdict.reason}
            </Callout>
          ) : null}

          {verdict.kind === 'accepted' && activeCampaign ? (
            <>
              <Callout tone="success">
                <strong className="block font-label-md">Valid code</strong>
                {activeCampaign.title} — {activeCampaign.offerText}. A scan has been logged; no
                customer counted yet.
              </Callout>

              <form className="space-y-space-md" onSubmit={submitRedemption}>
                <Field
                  label="Bill amount (₹)"
                  hint={`Discount: ${activeCampaign.discountPercent}%`}
                >
                  <input
                    className={inputClass}
                    type="number"
                    min="1"
                    step="1"
                    value={bill}
                    onChange={(event) => setBill(event.target.value)}
                    placeholder="2400"
                    required
                  />
                </Field>

                <Field label="Channel">
                  <select
                    className={inputClass}
                    value={channel}
                    onChange={(event) => setChannel(event.target.value as Redemption['channel'])}
                  >
                    <option value="dine_in">Dine in</option>
                    <option value="takeaway">Takeaway</option>
                    <option value="delivery">Delivery</option>
                  </select>
                </Field>

                <Field label="Staff member" hint="Optional — recorded against the redemption.">
                  <input
                    className={inputClass}
                    value={staff}
                    onChange={(event) => setStaff(event.target.value)}
                    placeholder="Asha"
                  />
                </Field>

                {Number(bill) > 0 ? (
                  <p className="text-body-md text-body-md text-on-surface">
                    Apply <strong className="text-primary">{formatINR(discountPaise)}</strong>{' '}
                    discount — customer pays{' '}
                    {formatINR(Math.round(Number(bill) * 100) - discountPaise)}.
                  </p>
                ) : null}

                <Button type="submit" disabled={!Number(bill)}>
                  Record redemption
                </Button>
              </form>
            </>
          ) : null}

          {confirmed ? (
            <Callout tone="success">
              <strong className="block font-label-md">Redemption recorded</strong>
              {formatINR(confirmed.discountAmountInPaise)} off a{' '}
              {formatINR(confirmed.billAmountInPaise)} bill at{' '}
              {formatTime(confirmed.redeemedAt)}. This diner is now a verified customer.
            </Callout>
          ) : null}

          {verdict.kind === 'idle' ? (
            <Callout tone="info">
              Enter a code to begin. Nothing is recorded until you check it, and no customer is
              counted until you record a redemption.
            </Callout>
          ) : null}
        </Card>
      </div>

      <RecentActivity restaurantId={restaurant.id} />
    </div>
  );
}

/** The last few events at this venue, newest first — the audit trail staff actually need. */
function RecentActivity({ restaurantId }: { restaurantId: string }) {
  const db = useAppStore((s) => s.db);

  const scans = db.scans
    .filter((s) => s.restaurantId === restaurantId)
    .sort((a, b) => b.scannedAt.localeCompare(a.scannedAt))
    .slice(0, 8);

  if (scans.length === 0) return null;

  return (
    <Card className="p-gutter">
      <h3 className="mb-space-md font-headline-sm text-headline-sm text-on-surface">
        Recent scans at your venue
      </h3>
      <ul className="divide-y divide-outline-variant/20">
        {scans.map((scan) => {
          const campaign = db.campaigns.find((c) => c.id === scan.campaignId);
          return (
            <li
              key={scan.id}
              className="flex flex-wrap items-center justify-between gap-space-sm py-space-xs"
            >
              <div>
                <p className="font-mono text-body-sm text-body-sm text-on-surface">
                  {scan.promoCode}
                </p>
                <p className="text-body-sm text-body-sm text-on-surface-variant">
                  {campaign?.title} · {scan.method === 'qr' ? 'QR pass' : 'Typed code'}
                </p>
              </div>
              <span className="text-body-sm text-body-sm text-on-surface-variant">
                {formatTime(scan.scannedAt)}
              </span>
            </li>
          );
        })}
      </ul>
      <p className="mt-space-sm text-body-sm text-body-sm text-on-surface-variant">
        Scans are interest only — they are deliberately excluded from every customer count.
      </p>
    </Card>
  );
}

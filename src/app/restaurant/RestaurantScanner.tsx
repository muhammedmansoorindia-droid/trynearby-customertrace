/**
 * Scan & redeem — the restaurant's front desk.
 *
 * <p>This is where the product's core distinction becomes physical, and it maps onto two separate
 * backend endpoints:
 *
 * <ul>
 *   <li>`POST /scan` records that a code was *seen*. It never creates a customer.</li>
 *   <li>`POST /redeem` confirms a verified customer visit, and is the only call that creates one.</li>
 * </ul>
 *
 * <p>All validation lives on the server, so this screen never re-implements the promo-code rules:
 * it shows the backend's own message and adds staff guidance. Camera scanning via `jsQR` is a peer
 * of manual entry rather than a fallback, because a laptop at the desk may have no webcam.
 */

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';

import { useMutation, useResource } from '@/app/useResource';
import { Badge, Button, Callout, Card, Field, SectionHeading, inputClass } from '@/app/ui';
import { explainScanError, scanApi } from '@/services/backend/scan';
import { campaignsApi } from '@/services/backend/campaigns';
import { formatINR, formatTime } from '@/utils/format';

/** Uppercase codes only; the backend is case-sensitive. */
function normalize(raw: string): string {
  return raw.trim().toUpperCase();
}

type Verdict =
  | { kind: 'idle' }
  | { kind: 'scanned'; campaignTitle: string; offerText: string }
  | { kind: 'refused'; reason: string };

export function RestaurantScanner() {
  const [entry, setEntry] = useState('');
  const [verdict, setVerdict] = useState<Verdict>({ kind: 'idle' });
  const [bill, setBill] = useState('');
  const [channel, setChannel] = useState<'DINE_IN' | 'TAKEAWAY' | 'DELIVERY'>('DINE_IN');
  const [staff, setStaff] = useState('');
  const [confirmed, setConfirmed] = useState<{ bill: number; customers: number } | null>(null);
  const [scannedCode, setScannedCode] = useState<string | null>(null);

  const canvasRef = useRef<HTMLCanvasElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const rafRef = useRef<number | null>(null);
  const [cameraOn, setCameraOn] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);

  // Live codes for the venue, so staff can tap instead of typing.
  const live = useResource(
    useCallback(async () => {
      const page = await campaignsApi.listForRestaurant({ status: 'ACTIVE' });
      return page.content.filter((c) => c.promoCode);
    }, []),
    [],
  );

  // ── Camera ────────────────────────────────────────────────────────────────
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
      if (!ctx) throw new Error('no canvas context');

      const tick = () => {
        if (video.readyState === video.HAVE_ENOUGH_DATA && canvas) {
          canvas.height = video.videoHeight;
          canvas.width = video.videoWidth;
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
          const image = ctx.getImageData(0, 0, canvas.width, canvas.height);
          const found = jsQR(image.data, image.width, image.height);
          if (found?.data) {
            setEntry(normalize(found.data));
            stopCamera();
            return;
          }
        }
        rafRef.current = requestAnimationFrame(tick);
      };
      rafRef.current = requestAnimationFrame(tick);
      setCameraOn(true);
    } catch {
      // Permission denial and no-camera both land here; manual entry still works.
      setCameraError('Camera permission was declined. Type the code instead.');
    }
  };

  // ── Actions ───────────────────────────────────────────────────────────────

  /**
   * Looks the code up, then records the scan.
   *
   * <p>Two calls, deliberately: `lookup` is read-only and gives staff the campaign details to
   * check against the customer in front of them; `scan` is the only write. If the scan is
   * refused, nothing is counted and the backend's reason is shown.
   */
  const check = useMutation(async (raw: string) => {
    const code = normalize(raw);
    const found = await scanApi.lookup(code);
    const result = await scanApi.scan(code, 'MANUAL', 'Front counter');
    return { code, found, result };
  });

  const onCheck = async () => {
    setConfirmed(null);
    setScannedCode(null);
    const outcome = await check.run(entry);
    if (!outcome) return;

    // The scan endpoint can accept the code but still report it invalid; trust its verdict.
    if (!outcome.result.valid) {
      setVerdict({ kind: 'refused', reason: outcome.result.message });
      return;
    }
    setScannedCode(outcome.code);
    setVerdict({
      kind: 'scanned',
      campaignTitle: outcome.result.campaignTitle ?? outcome.found.campaignTitle,
      offerText: outcome.result.offerText ?? outcome.found.offerText,
    });
  };

  const submitRedemption = useMutation(async (code: string) => {
    const amountPaise = Math.round(Number(bill) * 100);
    return scanApi.redeem({ promoCode: code, billPaise: amountPaise, channel });
  });

  const onRedeem = async (event: FormEvent) => {
    event.preventDefault();
    if (!scannedCode || !Number(bill)) return;

    const billRupees = Number(bill);
    const result = await submitRedemption.run(scannedCode);
    if (!result) return;

    setConfirmed({ bill: billRupees, customers: result.verifiedCustomers });
    setBill('');
    setStaff('');
    setScannedCode(null);
    setVerdict({ kind: 'idle' });
    setEntry('');
  };

  const billError = bill && Number(bill) <= 0 ? 'Enter a bill amount greater than zero.' : null;

  return (
    <div className="space-y-gutter">
      <SectionHeading
        title="Scan & redeem"
        subtitle="A scan proves interest; a redemption proves a customer."
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
            hint="Seven characters. Typed codes and QR passes behave identically."
          >
            <div className="flex gap-space-sm">
              <input
                className={inputClass}
                value={entry}
                onChange={(event) => setEntry(event.target.value.toUpperCase())}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && entry.trim()) void onCheck();
                }}
                placeholder="MK4014E"
                autoComplete="off"
                spellCheck={false}
                maxLength={7}
              />
              <Button onClick={() => void onCheck()} disabled={!entry.trim() || check.pending}>
                {check.pending ? 'Checking…' : 'Check'}
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

          {live.data?.length ? (
            <div>
              <p className="mb-space-xs font-label-md text-label-md text-on-surface">
                Or tap a live code
              </p>
              <div className="flex flex-wrap gap-space-xs">
                {live.data.map((campaign) => (
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
                    {campaign.promoCode}
                  </button>
                ))}
              </div>
            </div>
          ) : null}
        </Card>

        <Card className="space-y-space-md p-gutter">
          {check.error ? (
            <Callout tone="error">
              <strong className="block font-label-md">Could not check that code</strong>
              {explainScanError(check.error.code, check.error.message)}
            </Callout>
          ) : null}

          {verdict.kind === 'refused' ? (
            <Callout tone="error">
              <strong className="block font-label-md">Code refused</strong>
              {verdict.reason}
            </Callout>
          ) : null}

          {verdict.kind === 'scanned' ? (
            <>
              <Callout tone="success">
                <strong className="block font-label-md">Valid code</strong>
                {verdict.campaignTitle} — {verdict.offerText}. A scan has been logged; no customer
                counted yet.
              </Callout>

              <form className="space-y-space-md" onSubmit={onRedeem}>
                <Field label="Bill amount (₹)" hint="Required — a redemption is a settled visit.">
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
                {billError ? (
                  <p role="alert" className="text-body-sm text-body-sm text-error">
                    {billError}
                  </p>
                ) : null}

                <Field label="Channel">
                  <select
                    className={inputClass}
                    value={channel}
                    onChange={(event) =>
                      setChannel(event.target.value as 'DINE_IN' | 'TAKEAWAY' | 'DELIVERY')
                    }
                  >
                    <option value="DINE_IN">Dine in</option>
                    <option value="TAKEAWAY">Takeaway</option>
                    <option value="DELIVERY">Delivery</option>
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

                <Button type="submit" disabled={!Number(bill) || submitRedemption.pending}>
                  {submitRedemption.pending ? 'Recording…' : 'Record redemption'}
                </Button>
              </form>
            </>
          ) : null}

          {submitRedemption.error ? (
            <Callout tone="error">
              <strong className="block font-label-md">Redemption refused</strong>
              {explainScanError(submitRedemption.error.code, submitRedemption.error.message)}
            </Callout>
          ) : null}

          {confirmed ? (
            <Callout tone="success">
              <strong className="block font-label-md">Redemption recorded</strong>
              {formatINR(confirmed.bill)} bill settled at {formatTime(new Date().toISOString())}. This diner is now
              a verified customer — {confirmed.customers} so far on this campaign.
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
    </div>
  );
}

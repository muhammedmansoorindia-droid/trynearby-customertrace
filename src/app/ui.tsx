/**
 * Shared presentational primitives.
 *
 * Every class here is a Stitch token (bg-surface-container-lowest, text-headline-md,
 * p-space-lg …) so the three portals read as one product without a bespoke stylesheet.
 * Kept free of store access so they can be rendered in isolation by tests.
 */

import type { ReactNode } from 'react';

import { cn } from '@/utils';

// ── Surfaces ───────────────────────────────────────────────────────────

export function Card({
  children,
  className,
  as: Tag = 'div',
}: {
  children: ReactNode;
  className?: string;
  as?: 'div' | 'section' | 'article' | 'li';
}) {
  return (
    <Tag
      className={cn(
        'rounded-xl border border-outline-variant/30 bg-surface-container-lowest shadow-elevate-low',
        className,
      )}
    >
      {children}
    </Tag>
  );
}

export function SectionHeading({
  title,
  subtitle,
  action,
}: {
  title: string;
  subtitle?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-space-sm mb-space-md">
      <div>
        <h2 className="font-headline-md text-headline-md text-on-surface">{title}</h2>
        {subtitle ? (
          <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">{subtitle}</p>
        ) : null}
      </div>
      {action}
    </div>
  );
}

// ── Metrics ────────────────────────────────────────────────────────────

/**
 * A KPI tile.
 *
 * `hint` exists to carry the caveat under the number — e.g. "12 of 40 target reached" — so
 * the tile can never be read as more precise than the underlying data.
 */
export function StatTile({
  label,
  value,
  hint,
  tone = 'default',
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: 'default' | 'primary' | 'muted';
}) {
  const toneClass =
    tone === 'primary'
      ? 'text-primary'
      : tone === 'muted'
        ? 'text-on-surface-variant'
        : 'text-on-surface';

  return (
    <Card className="p-space-md">
      <p className="text-label-sm text-label-sm uppercase tracking-wide text-on-surface-variant">
        {label}
      </p>
      <p className={cn('font-display-md text-display-md mt-2', toneClass)}>{value}</p>
      {hint ? <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">{hint}</p> : null}
    </Card>
  );
}

/**
 * The funnel: scans → redemptions → verified customers.
 *
 * Rendered as an explicit three-step bar because the *relative widths* are the product's
 * central claim. A single blended number would hide exactly the drop-off this demo exists to
 * show.
 */

// ── Feedback ───────────────────────────────────────────────────────────

export function Badge({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full border px-3 py-1 font-label-sm text-label-sm',
        className ?? 'bg-surface-container text-on-surface-variant border-outline-variant/40',
      )}
    >
      {children}
    </span>
  );
}

export function Callout({
  tone,
  children,
}: {
  tone: 'info' | 'error' | 'success' | 'warning';
  children: ReactNode;
}) {
  const tones = {
    info: 'bg-surface-container-high text-on-surface border-outline-variant/40',
    error: 'bg-error-container text-on-error-container border-error/25',
    success: 'bg-secondary-container text-on-secondary-fixed border-secondary/25',
    // Warm tertiary palette, matching the Stitch design, for connection warnings.
    warning: 'bg-tertiary-container text-on-tertiary-fixed-variant border-tertiary/25',
  } as const;

  return (
    <div
      className={cn(
        'rounded-lg border px-space-md py-space-sm text-body-md text-body-md',
        tones[tone],
      )}
    >
      {children}
    </div>
  );
}

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="rounded-xl border border-dashed border-outline-variant/50 px-space-lg py-space-xl text-center">
      <p className="font-headline-sm text-headline-sm text-on-surface">{title}</p>
      {hint ? (
        <p className="text-body-sm text-body-sm text-on-surface-variant mt-1">{hint}</p>
      ) : null}
    </div>
  );
}

// ── Controls ───────────────────────────────────────────────────────────

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

export function Button({
  children,
  onClick,
  variant = 'primary',
  disabled,
  type = 'button',
  className,
  title,
}: {
  children: ReactNode;
  onClick?: () => void;
  variant?: ButtonVariant;
  disabled?: boolean;
  type?: 'button' | 'submit';
  className?: string;
  title?: string;
}) {
  const variants: Record<ButtonVariant, string> = {
    primary: 'bg-primary text-on-primary hover:bg-tertiary-container border border-transparent',
    secondary:
      'bg-surface-container-lowest text-on-surface hover:bg-surface-container-high border border-outline-variant/40',
    ghost: 'bg-transparent text-on-surface-variant hover:bg-surface-container border border-transparent',
    danger: 'bg-error text-on-error hover:brightness-110 border border-transparent',
  };

  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      title={title}
      className={cn(
        'inline-flex items-center justify-center gap-space-xs rounded-lg px-space-md py-space-sm',
        'font-label-md text-label-md transition-all duration-200 active:scale-[0.99]',
        'disabled:opacity-40 disabled:pointer-events-none',
        variants[variant],
        className,
      )}
    >
      {children}
    </button>
  );
}

export function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <label className="block">
      <span className="block font-label-md text-label-md text-on-surface mb-1">{label}</span>
      {children}
      {hint ? (
        <span className="block text-body-sm text-body-sm text-on-surface-variant mt-1">
          {hint}
        </span>
      ) : null}
    </label>
  );
}

export const inputClass =
  'w-full rounded-lg border border-outline-variant/50 bg-surface-container-lowest px-space-sm py-space-sm ' +
  'text-body-md text-body-md text-on-surface placeholder:text-outline focus-ring';

export function FunnelBar({
  scans,
  redemptions,
  customers,
}: {
  scans: number;
  redemptions: number;
  customers: number;
}) {
  const top = Math.max(scans, redemptions, customers, 1);
  const stages = [
    { label: 'Scans', value: scans, className: 'bg-surface-container-highest' },
    { label: 'Redemptions', value: redemptions, className: 'bg-primary-container' },
    { label: 'Verified customers', value: customers, className: 'bg-primary' },
  ];

  return (
    <div className="space-y-space-sm">
      {stages.map((stage) => (
        <div key={stage.label} className="flex items-center gap-space-sm">
          <span className="w-36 shrink-0 text-body-sm text-body-sm text-on-surface-variant">
            {stage.label}
          </span>
          <div className="h-8 flex-1 rounded-lg bg-surface-container overflow-hidden">
            <div
              className={cn('h-full rounded-lg transition-all', stage.className)}
              style={{ width: `${Math.max((stage.value / top) * 100, stage.value > 0 ? 4 : 0)}%` }}
            />
          </div>
          <span className="w-12 shrink-0 text-right font-headline-sm text-headline-sm text-on-surface">
            {stage.value.toLocaleString('en-IN')}
          </span>
        </div>
      ))}
    </div>
  );
}

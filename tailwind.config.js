/**
 * Customer Trace — Tailwind design tokens
 *
 * The `colors`, `borderRadius`, `spacing`, `fontFamily` and `fontSize` blocks below are
 * transcribed VERBATIM from the Google Stitch export's inline `tailwind.config` object
 * (see the `stitch-reference` directory). Every Stitch class name —
 * bg-surface-container-lowest, p-space-lg, text-headline-sm, gap-space-xs —
 * resolves natively against these tokens.
 *
 * DO NOT migrate this file to Tailwind v4. v4 derives the spacing scale from a single
 * --spacing multiplier and cannot resolve the custom *-space-* keys the whole design
 * system depends on. v3.4 is pinned deliberately.
 *
 * Per DESIGN.md, hierarchy rejects harsh generic shadows in favour of warm espresso-tinted
 * ambient glows and tactile 1px border demarcation.
 */

/** Warm, espresso-tinted ambient elevation recipes (DESIGN.md → "Elevation & Depth"). */
const elevation = {
  low: '0 2px 8px -2px rgba(33, 24, 19, 0.04), 0 1px 3px 0 rgba(33, 24, 19, 0.03)',
  medium: '0 8px 24px -4px rgba(33, 24, 19, 0.08), 0 3px 6px -1px rgba(33, 24, 19, 0.04)',
  high: '0 20px 40px -8px rgba(33, 24, 19, 0.12), 0 8px 16px -4px rgba(33, 24, 19, 0.06)',
};

/** Device safe-area insets. Stitch shipped pt-safe/pb-safe but its runtime defined them. */
const safeAreaPadding = {
  'pt-safe': 'padding-top: env(safe-area-inset-top, 0px)',
  'pb-safe': 'padding-bottom: env(safe-area-inset-bottom, 0px)',
  'pl-safe': 'padding-left: env(safe-area-inset-left, 0px)',
  'pr-safe': 'padding-right: env(safe-area-inset-right, 0px)',
};

export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      // ── Stitch palette, verbatim ──────────────────────────────────────────
      colors: {
        'on-secondary-fixed': '#1f1b13',
        'on-secondary': '#ffffff',
        background: '#fff8f5',
        'on-error-container': '#93000a',
        outline: '#897269',
        'surface-container-low': '#fff1ea',
        'inverse-primary': '#ffb596',
        'surface-container-lowest': '#ffffff',
        'inverse-surface': '#392e29',
        'on-primary': '#ffffff',
        tertiary: '#9f4114',
        'on-tertiary-fixed-variant': '#7e2c00',
        'on-secondary-fixed-variant': '#4c463c',
        'on-error': '#ffffff',
        'primary-fixed': '#ffdbcd',
        'on-tertiary-container': '#632000',
        'outline-variant': '#ddc1b6',
        surface: '#fff8f5',
        'surface-container-highest': '#f1dfd6',
        'on-secondary-container': '#6a6459',
        'error-container': '#ffdad6',
        'primary-fixed-dim': '#ffb596',
        'on-primary-container': '#612200',
        'on-background': '#231a15',
        'inverse-on-surface': '#ffede5',
        'tertiary-container': '#f27f4d',
        'on-surface-variant': '#56423a',
        error: '#ba1a1a',
        'on-surface': '#231a15',
        'surface-bright': '#fff8f5',
        'surface-container-high': '#f7e5dc',
        'secondary-container': '#ebe1d3',
        secondary: '#645e53',
        'surface-tint': '#9e4211',
        'surface-variant': '#f1dfd6',
        'tertiary-fixed': '#ffdbce',
        'surface-dim': '#e8d6ce',
        'secondary-fixed': '#cec5b8',
        'secondary-fixed-dim': '#cec5b8',
        'surface-container': '#fdeae1',
        'on-primary-fixed-variant': '#7c2e00',
        'secondary-fixed': '#ebe1d3',
        'tertiary-fixed-dim': '#ffb598',
        'on-tertiary': '#ffffff',
        'primary-container': '#f0804b',
        'on-tertiary-fixed': '#370e00',
        'on-primary-fixed': '#360f00',
        primary: '#9e4211',
      },
      borderRadius: {
        DEFAULT: '0.25rem',
        lg: '0.5rem',
        xl: '0.75rem',
        full: '9999px',
      },
      spacing: {
        'space-md': '1rem',
        margin: '1.5rem',
        'space-xs': '0.25rem',
        'gutter-sm': '1rem',
        gutter: '1.5rem',
        'margin-sm': '1rem',
        'margin-lg': '3rem',
        'space-sm': '0.5rem',
        'gutter-lg': '2rem',
        'space-lg': '1.5rem',
        'space-xl': '2.5rem',
      },
      fontFamily: {
        'body-lg': ['Inter'],
        'headline-sm': ['Inter'],
        'display-lg': ['Inter'],
        'body-sm': ['Inter'],
        'headline-md': ['Inter'],
        'label-sm': ['Inter'],
        'headline-lg': ['Inter'],
        'label-md': ['Inter'],
        'display-md-mobile': ['Inter'],
        'display-md': ['Inter'],
        'display-lg-mobile': ['Inter'],
        'body-md': ['Inter'],
      },
      fontSize: {
        'body-lg': ['16px', { lineHeight: '24px', letterSpacing: '-0.005em', fontWeight: '400' }],
        'headline-sm': ['18px', { lineHeight: '24px', letterSpacing: '-0.01em', fontWeight: '600' }],
        'display-lg': ['56px', { lineHeight: '64px', letterSpacing: '-0.03em', fontWeight: '800' }],
        'body-sm': ['12px', { lineHeight: '16px', letterSpacing: '0em', fontWeight: '400' }],
        'headline-md': ['22px', { lineHeight: '28px', letterSpacing: '-0.015em', fontWeight: '600' }],
        'label-sm': ['11px', { lineHeight: '14px', letterSpacing: '0.03em', fontWeight: '600' }],
        'headline-lg': ['30px', { lineHeight: '38px', letterSpacing: '-0.02em', fontWeight: '700' }],
        'label-md': ['13px', { lineHeight: '16px', letterSpacing: '0.01em', fontWeight: '600' }],
        'display-md-mobile': ['28px', { lineHeight: '34px', letterSpacing: '-0.02em', fontWeight: '700' }],
        'display-md': ['40px', { lineHeight: '48px', letterSpacing: '-0.025em', fontWeight: '700' }],
        'display-lg-mobile': ['36px', { lineHeight: '42px', letterSpacing: '-0.025em', fontWeight: '800' }],
        'body-md': ['14px', { lineHeight: '20px', letterSpacing: '0em', fontWeight: '400' }],
      },
      // ── Additions layered on top of the Stitch tokens ─────────────────────
      boxShadow: {
        // Stitch inline shadows, promoted to named utilities.
        'elevate-low': elevation.low,
        'elevate-medium': elevation.medium,
        'elevate-high': elevation.high,
        topbar: '0 -2px 12px rgba(33, 24, 19, 0.05)',
      },
      maxWidth: {
        // DESIGN.md → desktop content caps at 1280px.
        canvas: '1280px',
      },
    },
  },
  plugins: [
    // Restores pt-safe / pb-safe, which the Stitch runtime provided but never emitted.
    function ({ addUtilities }) {
      addUtilities(safeAreaPadding);
      // DESIGN.md → input focus is an ambient warm glow, not a hard ring.
      addUtilities({
        '.focus-ring': {
          outline: 'none',
          borderColor: '#f0804b',
          boxShadow: '0 0 0 3px rgba(240, 128, 75, 0.15)',
        },
        '.focus-ring-inset': {
          outline: 'none',
          boxShadow: '0 0 0 3px rgba(240, 128, 75, 0.15)',
        },
      });
    },
  ],
};

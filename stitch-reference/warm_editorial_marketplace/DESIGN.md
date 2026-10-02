---
name: Warm Editorial Marketplace
colors:
  surface: '#fff8f5'
  surface-dim: '#e8d6ce'
  surface-bright: '#fff8f5'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#fff1ea'
  surface-container: '#fdeae1'
  surface-container-high: '#f7e5dc'
  surface-container-highest: '#f1dfd6'
  on-surface: '#231a15'
  on-surface-variant: '#56423a'
  inverse-surface: '#392e29'
  inverse-on-surface: '#ffede5'
  outline: '#897269'
  outline-variant: '#ddc1b6'
  surface-tint: '#9e4211'
  primary: '#9e4211'
  on-primary: '#ffffff'
  primary-container: '#f0804b'
  on-primary-container: '#612200'
  inverse-primary: '#ffb596'
  secondary: '#645e53'
  on-secondary: '#ffffff'
  secondary-container: '#ebe1d3'
  on-secondary-container: '#6a6459'
  tertiary: '#9f4114'
  on-tertiary: '#ffffff'
  tertiary-container: '#f27f4d'
  on-tertiary-container: '#632000'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#ffdbcd'
  primary-fixed-dim: '#ffb596'
  on-primary-fixed: '#360f00'
  on-primary-fixed-variant: '#7c2e00'
  secondary-fixed: '#ebe1d3'
  secondary-fixed-dim: '#cec5b8'
  on-secondary-fixed: '#1f1b13'
  on-secondary-fixed-variant: '#4c463c'
  tertiary-fixed: '#ffdbce'
  tertiary-fixed-dim: '#ffb598'
  on-tertiary-fixed: '#370e00'
  on-tertiary-fixed-variant: '#7e2c00'
  background: '#fff8f5'
  on-background: '#231a15'
  surface-variant: '#f1dfd6'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 56px
    fontWeight: '800'
    lineHeight: 64px
    letterSpacing: -0.03em
  display-lg-mobile:
    fontFamily: Inter
    fontSize: 36px
    fontWeight: '800'
    lineHeight: 42px
    letterSpacing: -0.025em
  display-md:
    fontFamily: Inter
    fontSize: 40px
    fontWeight: '700'
    lineHeight: 48px
    letterSpacing: -0.025em
  display-md-mobile:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 34px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Inter
    fontSize: 30px
    fontWeight: '700'
    lineHeight: 38px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.015em
  headline-sm:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
    letterSpacing: -0.01em
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: -0.005em
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0em
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0em
  label-md:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.01em
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.03em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1.5rem
  gutter-sm: 1rem
  gutter-lg: 2rem
  margin: 1.5rem
  margin-sm: 1rem
  margin-lg: 3rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2.5rem
---

## Brand & Style

This design system establishes an authentic, hospitality-driven digital environment that bridges high-touch culinary curation with operational SaaS utility. The emotional tone avoids both sterile enterprise software tropes and hyper-saturated social gimmicks, projecting instead the tactile warmth of printed food journals, independent neighborhood guides, and contemporary bespoke platforms.

The aesthetic philosophy centers on **warm editorial minimalism**:
- High-contrast, publication-grade typographic hierarchy.
- Sun-bleached linen canvases grounded by deep espresso typography.
- Warm terracotta-orange accents that draw the eye intentionally toward collaboration, discovery, and conversion without visually shouting.
- Tactile, sun-diffused depth that communicates comfort, trustworthiness, and neighborhood intimacy.

## Colors

The palette draws strictly from natural terracotta, toasted pastry, espresso, and unbleached fiber. Cold digital tones, neon accents, and stark blue-grays are prohibited.

### Palette Architecture
- **Primary (`#F0804B`)**: Vibrant sunlit coral orange. Used strictly for prime calls-to-action, key statuses, and intentional editorial italic emphases.
- **Primary Pressed/Dark (`#D96C3C`)**: Deeper baked-terracotta tone for hover, active states, and dense typography badges requiring accessibility compliance.
- **Neutral Deep / Text (`#211813`)**: Roasted espresso black. Delivers literary contrast without the harsh, synthetic glare of `#000000`.
- **Neutral Darkest (`#171310`)**: Deep charcoal coffee for prominent headlines and high-emphasis display marks.
- **Canvas Base (`#FCFAF6`)**: Warm oat-milk/linen base canvas for all screen surfaces.
- **Secondary Surface / Pale Cream (`#F5EBDD`)**: Subtle parchment tone for secondary structural panels, active filters, and table/card groupings.
- **Structural Outlines (`#EAE2D7`)**: Sun-warmed beige for delicate 1px dividers, card containment, and unselected state lines.
- **Muted Text (`#82776E`)**: Warm stone gray for secondary details, metadata timestamps, and form helpers.
- **Elevated Surfaces (`#FFFFFF`)**: Pure chalk white reserved for interactive cards, dialogs, and popovers floating over the `#FCFAF6` canvas.

## Typography

The type system pairs precise geometric structure with warm, literary editorial inflection:
- **Hero & Headline Voice**: Display weights use tight tracking (`-0.02em` to `-0.03em`) and heavy letterforms (`700` to `800`) to anchor the screen with physical print authority.
- **Editorial Emphases**: Selected headline words should be styled in `italic` font style combined with primary color `#F0804B` to evoke curated magazine features.
- **SaaS Legibility**: Body copy is kept crisp, compact, and neutral, relying on generous line height ratios to preserve readability in complex creator dashboards and transaction menus.
- **Labels & Microcopy**: Form labels and badge typography use medium-to-semibold weights with slight tracking adjustments to maintain clarity against warm tinted backgrounds.

## Layout & Spacing

The layout is built upon an 8pt architectural rhythm, utilizing deliberate negative space to create an unhurried, calm viewing experience.

- **Desktop (>= 1024px)**: 12-column responsive layout capped at a maximum width of `1280px`. Gutter is set to `2rem` (`space-lg`), with margins scaling gracefully at `3rem`.
- **Tablet (768px - 1023px)**: 8-column layout with `1.5rem` gutters and `2rem` outer padding.
- **Mobile (< 768px)**: 4-column layout with `1rem` gutters and `1rem` margins. Heavy multi-column content stacks into vertical narrative streams.
- **Rhythm & Cadence**: Information blocks must breathe. Card sections leverage `space-xl` gaps, while related micro-elements (chips, metadata pills) maintain snug `space-xs` and `space-sm` attachments.

## Elevation & Depth

Visual hierarchy rejects harsh, generic drop shadows in favor of soft, warm-tinted ambient glows and tactile border demarcation:

- **Border Hierarchy**: Resting surfaces rely primarily on a single crisp `1px solid #EAE2D7` boundary over `#FCFAF6` canvases to establish presence.
- **Ambient Warm Shadows**: Elevation utilizes warm espresso-tinted shadows rather than cold grays:
  - *Low Elevation (Cards, Floating Pills)*: `0 2px 8px -2px rgba(33, 24, 19, 0.04), 0 1px 3px 0 rgba(33, 24, 19, 0.03)`
  - *Medium Elevation (Hover Cards, Dropdown Popovers)*: `0 8px 24px -4px rgba(33, 24, 19, 0.08), 0 3px 6px -1px rgba(33, 24, 19, 0.04)`
  - *High Elevation (Modals, Slide-overs)*: `0 20px 40px -8px rgba(33, 24, 19, 0.12), 0 8px 16px -4px rgba(33, 24, 19, 0.06)`
- **Layering Pattern**: Canvas `#FCFAF6` -> Inset Panels `#F5EBDD` -> Elevated Cards `#FFFFFF`.

## Shapes

The geometric framework establishes an inviting balance between clean modern architecture and approachable consumer comfort:
- Standard interactive elements (input fields, action cards, panels) employ `0.5rem` (`roundedness: 2`) border radii.
- Medium components and floating panels scale to `1rem` (`rounded-lg`).
- Status tags, category filters, and notification markers adopt continuous curvature pill forms (`9999px`) to maintain contrast with square media tiles and imagery.

## Components

### Buttons
- **Primary**: Solid `#F0804B` background, `#FFFFFF` text, `font-weight: 600`, border radius `0.5rem`. Hover transitions to `#D96C3C`. Focus ring: `2px solid #FCFAF6` + `2px solid #F0804B`.
- **Secondary**: Surface `#FFFFFF`, border `1px solid #EAE2D7`, text `#211813`. Hover shifts background to `#F5EBDD`.
- **Tertiary / Ghost**: Transparent background, text `#211813`. Hover displays light tint `#F5EBDD` at `60%` opacity.

### Chips & Badges
- **Pill Badges**: Full pill radius (`9999px`), `font-size: 11px`, `font-weight: 600`.
  - *Warm Tint (Creator niches, cuisine types)*: Background `#F5EBDD`, text `#211813`, border `1px solid #EAE2D7`.
  - *Active / Selected Filter*: Background `#F0804B`, text `#FFFFFF`, border `1px solid #F0804B`.
  - *Status Indicator (Live, Available)*: Background `rgba(240, 128, 75, 0.12)`, text `#D96C3C`.

### Cards & Marketplace Tiles
- **Restaurant / Creator Profile Cards**: Background `#FFFFFF`, border `1px solid #EAE2D7`, radius `1rem` (`rounded-lg`), padding `space-md` to `space-lg`. Media headers use `0.75rem` inner radius with subtle `0.5px` border overlay. Hover triggers `Medium Elevation` warm shadow lift.

### Input Fields & Controls
- **Inputs**: Height `44px`, background `#FFFFFF`, border `1px solid #EAE2D7`, text `#211813`, placeholder `#82776E`. Focus state transitions border to `#F0804B` with an ambient glow (`box-shadow: 0 0 0 3px rgba(240, 128, 75, 0.15)`).
- **Checkboxes & Radios**: Unchecked border `1.5px solid #EAE2D7`, background `#FFFFFF`. Checked background `#F0804B` with crisp `#FFFFFF` tick or dot.

### Lists & Dividers
- **Dividers**: Ultra-crisp `1px solid #EAE2D7`, no drop shadow or bevel.
- **Dashboard Data Lists**: Alternating hover state via `#F5EBDD` at `40%` opacity, retaining clean vertical separation through generous row padding (`12px 16px`).

### Bespoke Domain Components
- **Collab Offer Banner**: Cream `#F5EBDD` surface container with a left-accent border (`4px solid #F0804B`), featuring espresso typography and a micro pill tag indicating compensation/perks.
- **Creator Metric Card**: `#FFFFFF` container showcasing large numeric counters (`display-md`) paired with warm muted microcopy (`#82776E`) and mini trend tags.
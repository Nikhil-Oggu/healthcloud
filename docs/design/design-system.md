# HealthCloud Design System — "Care Constellation"

> The durable source of truth for HealthCloud's visual design. Like `CLAUDE.md` is for engineering
> rules, this file keeps every page visually consistent so we don't drift as the UI grows.
> Status: **spec agreed; implementation in progress** (UI/Design track, Slice 1 = theme foundation).

## 1. Identity & guiding principle

- **Concept:** *Care Constellation* — the product's idea made visible as a **connected network**
  (patients ↔ providers ↔ coordinators). The design should feel unique, considered, and trustworthy —
  not a template.
- **Brand mark:** a gradient rounded-square logo + the wordmark **"HealthCloud"** set in Space Grotesk.
- **The hybrid rule (important):**
  - **Dark, bespoke Constellation** on the surfaces where eyes land first — **landing, login, dashboard hero**
    (the animated network, the glow, glassy cards). This is the "make a recruiter stop" moment.
  - **Light and highly readable** everywhere in the **deep app** — the work queues, forms, detail pages.
    Data-dense screens must be comfortable to work in; we carry the identity through the accent color,
    rounded shapes, typography, and the network mark, not through darkness.

## 2. Color tokens

| Token | Light app | Dark hero |
|---|---|---|
| Primary (teal) | `#0d9488` — text/hover `#0f766e` | `#5eead4` |
| Accent (indigo) | `#4f46e5` | `#7c9cff` |
| Background | `#f6f8fb` | `#070b18` |
| Surface / card | `#ffffff` | `#0e1428` (glassy) |
| Border / divider | `#e6e9f0` | `#1d2744` |
| Text (primary) | `#0f1729` | `#eaf0ff` |
| Text (muted) | `#5b6576` | `#8a97b8` |
| Signature gradient | *(accents only)* | `linear-gradient(100deg, #5eead4, #7c9cff)` |

**Status colors** (used by chips/badges across all 8 work queues — AA-contrast on light):

| Meaning | Color |
|---|---|
| Success / adjudicated / approved | `#0f766e` |
| Info / submitted / in-progress | `#4f46e5` |
| Warning / needs-info / pending | `#9a6b06` |
| Error / rejected / denied | `#b3392f` |

## 3. Typography

- **Headings / display:** **Space Grotesk** (weights 500–700).
- **Body / UI:** **Inter** (weights 400–600).
- **Codes / IDs / money:** **IBM Plex Mono** (claim numbers, auth numbers, correlation IDs, amounts).
- Loaded via a Google Fonts `<link>` in `frontend/index.html`.

**Scale** (approx, tuned in the theme): h1 34 · h2 26 · h3 20 · h4 17 · body 15 · small 13 · caption 12.
Buttons: **no uppercase transform**; medium weight.

## 4. Shape, elevation, spacing

- **Radius:** cards/panels 12px · buttons 10px · chips/pills 999px.
- **Elevation:** rest `0 1px 2px rgba(15,23,41,.06)` · raised `0 12px 30px -12px rgba(15,23,41,.18)`.
  Soft, never heavy default MUI shadows.
- **Spacing:** 8px rhythm (MUI default spacing unit).
- **Layout:** page content max-width **1120px**, **16px** side gutter, no horizontal scroll at phone width.

## 5. Component conventions (MUI theme defaults)

These are set once in `frontend/src/theme/index.ts` so every page inherits them — no per-page styling.

- **AppBar / shell:** light, subtle bottom divider (not the loud default blue); brand mark on the left.
- **Card / Paper:** white surface, 1px `#e6e9f0` border, radius 12, rest shadow.
- **Button:** primary = teal (a subtle teal→indigo gradient allowed on the main CTA only); no uppercase.
- **TableHead:** tinted background, uppercase 11px muted labels; row hover tint; comfortable density.
- **Chip:** pill, status colors from §2; used consistently for every status across the app.
- **TextField:** consistent size/spacing; clear focus ring in the accent color.

## 6. Motion & accessibility

- The **constellation animation** runs only where it is the hero (landing/login/dashboard band).
- **All motion honors `prefers-reduced-motion`** (static fallback).
- **WCAG 2.2 AA-aligned** (consistent with the Phase 9 accessibility work): AA contrast maintained,
  every interactive element keyboard-reachable, visible focus, single `<h1>` per page via `PageHeading`,
  landmarks and skip-link preserved.

## 7. What's fixed vs. what we tune live

- **Fixed upfront (this spec):** colors, fonts, spacing, radii, elevation, component defaults, identity.
  Slice 1 codes exactly this — no guessing.
- **Tuned in live browser preview:** page-level layout choices (sidebar vs top-nav, dashboard
  composition), and the intensity of the accent/motion to taste.

## 8. Build order (UI/Design track)

1. **Design foundation** — theme + fonts + brand mark (this spec, in `theme/index.ts`). Restyles all pages.
2. **App shell + navigation** — sidebar + refined top bar + a shared page-header pattern.
3. **Signature landing + login** — the dark animated Constellation hero.
4. **Role-aware dashboard** — stat cards + constellation hero band + quick links to the queues.
5. **Work-queues / tables polish** — all 8 queues, one consistent pattern.
6. **Forms + detail pages polish.**
7. *(optional)* dark-mode toggle + final consistency + motion sweep.

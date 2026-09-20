import { alpha, createTheme } from '@mui/material/styles'

/**
 * HealthCloud design system — "Care Constellation".
 * The concrete token spec lives in docs/design/design-system.md; this file is its implementation.
 *
 * The app runs on the LIGHT variant (readable, data-dense screens). The DARK-hero tokens below are
 * exported for the bespoke Constellation surfaces (landing / login / dashboard hero) built in later
 * slices — they are not applied globally.
 */

// Dark-hero surface tokens (used by the landing/login/dashboard hero components, later slices).
export const constellation = {
  dark: {
    bg: '#070b18',
    surface: '#0e1428',
    border: '#1d2744',
    text: '#eaf0ff',
    muted: '#8a97b8',
    teal: '#5eead4',
    indigo: '#7c9cff',
    gradient: 'linear-gradient(100deg, #5eead4, #7c9cff)',
  },
  // The accent gradient tuned for the light app (used sparingly, e.g. the primary CTA).
  gradient: 'linear-gradient(100deg, #0d9488, #4f46e5)',
} as const

const TEAL = '#0d9488'
const TEAL_DARK = '#0f766e'
const INDIGO = '#4f46e5'

const HEADING = '"Space Grotesk", "Inter", system-ui, sans-serif'
const BODY = '"Inter", system-ui, -apple-system, "Segoe UI", sans-serif'
export const MONO = '"IBM Plex Mono", ui-monospace, "SFMono-Regular", monospace'

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: TEAL, dark: TEAL_DARK, contrastText: '#ffffff' },
    secondary: { main: INDIGO, contrastText: '#ffffff' },
    success: { main: '#0f766e', contrastText: '#ffffff' },
    info: { main: INDIGO, contrastText: '#ffffff' },
    warning: { main: '#9a6b06', contrastText: '#ffffff' },
    error: { main: '#b3392f', contrastText: '#ffffff' },
    background: { default: '#f6f8fb', paper: '#ffffff' },
    text: { primary: '#0f1729', secondary: '#5b6576' },
    divider: '#e6e9f0',
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: BODY,
    h1: { fontFamily: HEADING, fontWeight: 700, fontSize: '2.125rem', letterSpacing: '-0.02em' },
    h2: { fontFamily: HEADING, fontWeight: 700, fontSize: '1.625rem', letterSpacing: '-0.01em' },
    h3: { fontFamily: HEADING, fontWeight: 600, fontSize: '1.25rem', letterSpacing: '-0.01em' },
    h4: { fontFamily: HEADING, fontWeight: 600, fontSize: '1.0625rem' },
    h5: { fontFamily: HEADING, fontWeight: 600 },
    h6: { fontFamily: HEADING, fontWeight: 600 },
    button: { textTransform: 'none', fontWeight: 600 },
  },
  components: {
    // Subtle canvas wash (Slice 8 depth pass): a whisper of teal top-right + indigo bottom-left over
    // the soft grey, fixed so it doesn't scroll — gives the whole app quiet depth instead of a flat
    // white field, without touching text contrast. Kept very low-alpha so it reads as light, not tinted.
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          backgroundColor: '#f6f8fb',
          backgroundImage: `radial-gradient(1000px 620px at 100% -12%, rgba(13,148,136,0.06), transparent 60%),
            radial-gradient(900px 600px at -12% 112%, rgba(79,70,229,0.05), transparent 55%)`,
          backgroundAttachment: 'fixed',
          backgroundRepeat: 'no-repeat',
        },
      },
    },
    // Light shell instead of the loud default blue AppBar (the shell is redesigned in Slice 2).
    MuiAppBar: {
      defaultProps: { color: 'inherit', elevation: 0 },
      styleOverrides: {
        root: {
          backgroundColor: '#ffffff',
          color: '#0f1729',
          borderBottom: '1px solid #e6e9f0',
        },
      },
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          border: '1px solid #e6e9f0',
          borderRadius: 14,
          // Soft layered shadow (Slice 8) so cards lift gently off the washed canvas.
          boxShadow: '0 1px 2px rgba(15,23,41,.04), 0 6px 20px -12px rgba(15,23,41,.14)',
        },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { borderRadius: 10, paddingInline: 18 },
      },
    },
    MuiChip: {
      styleOverrides: {
        // Soft, tinted status chips (light colored background + strong colored text) instead of solid
        // fills — the premium look, applied globally so every status chip across all queues + detail
        // pages restyles at once. Outlined chips (nav/identity) keep their border.
        root: ({ theme, ownerState }) => {
          const color = ownerState.color
          const palette = theme.palette as unknown as Record<string, { main: string; dark?: string }>
          const isPaletteColor = !!color && color !== 'default' && !!palette[color]?.main
          return {
            fontWeight: 600,
            borderRadius: 999,
            ...(ownerState.variant === 'filled' && isPaletteColor
              ? {
                  backgroundColor: alpha(palette[color].main, 0.12),
                  color: palette[color].dark ?? palette[color].main,
                }
              : {}),
          }
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: { borderColor: '#e6e9f0' },
        head: {
          backgroundColor: '#f6f8fb',
          color: '#5b6576',
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.06em',
          textTransform: 'uppercase',
        },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        // Faint zebra (Slice 8) so a big queue table doesn't read as one flat white sheet; the
        // head row is the sole child of its <thead> (nth-of-type 1), so it stays untinted. Hover
        // wins over the stripe.
        root: {
          '&:nth-of-type(even)': { backgroundColor: 'rgba(15,23,41,0.018)' },
          '&.MuiTableRow-hover:hover': { backgroundColor: 'rgba(13,148,136,0.06)' },
        },
      },
    },
    MuiLink: {
      defaultProps: { underline: 'hover' },
      styleOverrides: { root: { fontWeight: 500 } },
    },
  },
})

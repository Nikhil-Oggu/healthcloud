import { createTheme, type Theme } from '@mui/material/styles'

/**
 * HealthCloud design system — "Care Constellation".
 * The concrete token spec lives in docs/design/design-system.md; this file is its implementation.
 *
 * The app supports LIGHT and DARK color schemes (Slice 9). MUI CSS variables + `colorSchemes` drive
 * the switch: `defaultMode="system"` (set on the provider) follows the viewer's device preference, a
 * Light/Dark/System toggle (see components/ThemeToggle) lets them override, and the choice persists in
 * localStorage. The DARK scheme echoes the Constellation hero (deep navy + brighter teal/indigo) so the
 * whole app feels like the login/dashboard hero extended across every screen.
 *
 * The bespoke always-dark hero surfaces (login, dashboard hero band) use the `constellation` tokens
 * below directly, so they render identically in both modes — they're mode-independent by design.
 */

// Dark-hero surface tokens (used by the login / dashboard hero components directly).
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
  // The accent gradient tuned for the light app (used sparingly, e.g. the stat-card top accent).
  gradient: 'linear-gradient(100deg, #0d9488, #4f46e5)',
} as const

// Brand — light scheme.
const TEAL = '#0d9488'
const TEAL_DARK = '#0f766e'
const INDIGO = '#4f46e5'
// Brand — dark scheme (brighter so links/icons/accents pop on the deep navy).
const TEAL_BRIGHT = '#2dd4bf'
const INDIGO_BRIGHT = '#818cf8'

const HEADING = '"Space Grotesk", "Inter", system-ui, sans-serif'
const BODY = '"Inter", system-ui, -apple-system, "Segoe UI", sans-serif'
export const MONO = '"IBM Plex Mono", ui-monospace, "SFMono-Regular", monospace'

export const theme = createTheme({
  // CSS variables + a class selector on <html> so the mode can be toggled (and set pre-hydration by the
  // inline script in index.html to avoid a light-then-dark flash). 'class' → <html class="light|dark">.
  cssVariables: { colorSchemeSelector: 'class' },
  colorSchemes: {
    light: {
      palette: {
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
    },
    dark: {
      palette: {
        primary: { main: TEAL_BRIGHT, dark: TEAL, light: '#5eead4', contrastText: '#04201c' },
        secondary: { main: INDIGO_BRIGHT, light: '#a5b4fc', contrastText: '#0b1020' },
        success: { main: '#34d399', light: '#6ee7b7', contrastText: '#04201c' },
        info: { main: INDIGO_BRIGHT, light: '#a5b4fc', contrastText: '#0b1020' },
        warning: { main: '#fbbf24', light: '#fcd34d', contrastText: '#241a02' },
        error: { main: '#f87171', light: '#fca5a5', contrastText: '#2a0b09' },
        background: { default: '#070b18', paper: '#0e1428' },
        text: { primary: '#eaf0ff', secondary: '#9aa7c7' },
        divider: 'rgba(148,163,214,0.16)',
      },
    },
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
    // the scheme background, fixed so it doesn't scroll. Dark mode gets a slightly stronger, brighter
    // glow so the deep navy reads like the Constellation hero world rather than a flat black field.
    MuiCssBaseline: {
      styleOverrides: {
        body: ({ theme }: { theme: Theme }) => ({
          backgroundColor: '#f6f8fb',
          backgroundImage: `radial-gradient(1000px 620px at 100% -12%, rgba(13,148,136,0.06), transparent 60%),
            radial-gradient(900px 600px at -12% 112%, rgba(79,70,229,0.05), transparent 55%)`,
          backgroundAttachment: 'fixed',
          backgroundRepeat: 'no-repeat',
          ...theme.applyStyles('dark', {
            backgroundColor: '#070b18',
            backgroundImage: `radial-gradient(1000px 640px at 100% -12%, rgba(45,212,191,0.10), transparent 60%),
              radial-gradient(900px 620px at -12% 112%, rgba(129,140,248,0.10), transparent 55%)`,
          }),
        }),
      },
    },
    // Light shell instead of the loud default blue AppBar (the shell is redesigned in Slice 2).
    MuiAppBar: {
      defaultProps: { color: 'inherit', elevation: 0 },
      styleOverrides: {
        root: ({ theme }) => ({
          backgroundColor: theme.vars.palette.background.paper,
          color: theme.vars.palette.text.primary,
          borderBottom: `1px solid ${theme.vars.palette.divider}`,
        }),
      },
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: ({ theme }) => ({
          border: `1px solid ${theme.vars.palette.divider}`,
          borderRadius: 14,
          backgroundImage: 'none',
          // Soft layered shadow (Slice 8) so cards lift gently off the washed canvas.
          boxShadow: '0 1px 2px rgba(15,23,41,.04), 0 6px 20px -12px rgba(15,23,41,.14)',
          ...theme.applyStyles('dark', {
            boxShadow: '0 1px 2px rgba(0,0,0,.45), 0 6px 20px -12px rgba(0,0,0,.65)',
          }),
        }),
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
        // Soft, tinted status chips (light-tint background + a same-color ring + strong colored text)
        // instead of solid fills — applied globally so every status chip across all queues + detail pages
        // restyles at once. The ring + label padding give short labels (ACTIVE / REQUESTED) real presence
        // and proper width. Uses the CSS-variable channel tokens so the tint follows the active scheme;
        // dark mode uses a stronger tint + lighter text so the chip stays legible on the deep navy.
        // Outlined chips (nav / identity) keep their own border.
        root: ({ theme, ownerState }) => {
          const color = ownerState.color
          const isPaletteColor =
            !!color &&
            color !== 'default' &&
            ['primary', 'secondary', 'success', 'info', 'warning', 'error'].includes(color)
          return {
            fontWeight: 700,
            borderRadius: 999,
            letterSpacing: '0.02em',
            '& .MuiChip-label': { paddingInline: 11 },
            ...(ownerState.variant === 'filled' && isPaletteColor
              ? {
                  backgroundColor: `rgba(var(--mui-palette-${color}-mainChannel) / 0.16)`,
                  color: `var(--mui-palette-${color}-dark)`,
                  border: `1px solid rgba(var(--mui-palette-${color}-mainChannel) / 0.34)`,
                  ...theme.applyStyles('dark', {
                    backgroundColor: `rgba(var(--mui-palette-${color}-mainChannel) / 0.24)`,
                    color: `var(--mui-palette-${color}-light)`,
                    border: `1px solid rgba(var(--mui-palette-${color}-mainChannel) / 0.45)`,
                  }),
                }
              : {}),
            // A defined neutral for a `default` filled chip (e.g. an INACTIVE status) so it isn't washed out.
            ...(ownerState.variant === 'filled' && color === 'default'
              ? {
                  backgroundColor: 'rgba(15,23,41,0.06)',
                  color: theme.vars.palette.text.secondary,
                  border: '1px solid rgba(15,23,41,0.12)',
                  ...theme.applyStyles('dark', {
                    backgroundColor: 'rgba(255,255,255,0.08)',
                    border: '1px solid rgba(255,255,255,0.14)',
                  }),
                }
              : {}),
          }
        },
      },
    },
    // Tables read as a lifted, contained surface (not a flat sheet on the canvas). Pages use
    // `TableContainer component={Paper} elevation={0}`, so this override owns the whole look.
    MuiTableContainer: {
      styleOverrides: {
        root: ({ theme }) => ({
          backgroundColor: theme.vars.palette.background.paper,
          border: `1px solid ${theme.vars.palette.divider}`,
          borderRadius: 14,
          overflow: 'hidden',
          boxShadow: '0 1px 2px rgba(15,23,41,.04), 0 10px 28px -18px rgba(15,23,41,.22)',
          ...theme.applyStyles('dark', {
            boxShadow: '0 1px 2px rgba(0,0,0,.5), 0 12px 30px -18px rgba(0,0,0,.7)',
          }),
        }),
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: ({ theme }) => ({ borderColor: theme.vars.palette.divider, paddingTop: 14, paddingBottom: 14 }),
        // A subtly teal-tinted header rule (not the washed grey) so the table reads as branded + intentional.
        head: ({ theme }) => ({
          backgroundColor: 'rgba(13,148,136,0.05)',
          color: theme.vars.palette.text.secondary,
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.07em',
          textTransform: 'uppercase',
          borderBottom: `1px solid ${theme.vars.palette.divider}`,
          ...theme.applyStyles('dark', { backgroundColor: 'rgba(45,212,191,0.08)' }),
        }),
      },
    },
    // Form inputs get a soft filled resting state + a clear teal focus ring, so forms read as deliberate
    // rather than bare fields on white. Applies to every outlined TextField/Select across the app.
    MuiOutlinedInput: {
      styleOverrides: {
        root: ({ theme }) => ({
          backgroundColor: theme.vars.palette.background.default,
          transition: 'background-color .15s, box-shadow .15s',
          '& .MuiOutlinedInput-notchedOutline': { borderColor: theme.vars.palette.divider },
          '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: theme.vars.palette.text.secondary },
          '&.Mui-focused': {
            backgroundColor: theme.vars.palette.background.paper,
            '& .MuiOutlinedInput-notchedOutline': {
              borderColor: theme.vars.palette.primary.main,
              borderWidth: 2,
            },
          },
        }),
      },
    },
    MuiTableRow: {
      styleOverrides: {
        // Faint zebra (Slice 8) so a big queue table doesn't read as one flat sheet; the head row is
        // the sole child of its <thead> (nth-of-type 1), so it stays untinted. Hover wins over the
        // stripe. Dark mode flips the ink to a faint light tint.
        root: ({ theme }) => ({
          '&:nth-of-type(even)': { backgroundColor: 'rgba(15,23,41,0.018)' },
          '&.MuiTableRow-hover:hover': { backgroundColor: 'rgba(13,148,136,0.06)' },
          ...theme.applyStyles('dark', {
            '&:nth-of-type(even)': { backgroundColor: 'rgba(255,255,255,0.03)' },
            '&.MuiTableRow-hover:hover': { backgroundColor: 'rgba(45,212,191,0.10)' },
          }),
        }),
      },
    },
    MuiLink: {
      defaultProps: { underline: 'hover' },
      styleOverrides: { root: { fontWeight: 500 } },
    },
  },
})

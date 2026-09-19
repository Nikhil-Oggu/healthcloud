import { createTheme } from '@mui/material/styles'

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
          boxShadow: '0 1px 2px rgba(15,23,41,.06)',
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
        root: { fontWeight: 600, borderRadius: 999 },
      },
    },
    MuiTableCell: {
      styleOverrides: {
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
    MuiLink: {
      defaultProps: { underline: 'hover' },
      styleOverrides: { root: { fontWeight: 500 } },
    },
  },
})

import { useState } from 'react'
import { Box, Button, Container, Dialog, DialogContent, Divider, IconButton, Stack, Typography } from '@mui/material'
import type { SvgIconComponent } from '@mui/icons-material'
import PersonOutlinedIcon from '@mui/icons-material/PersonOutlined'
import MedicalServicesOutlinedIcon from '@mui/icons-material/MedicalServicesOutlined'
import AccountTreeOutlinedIcon from '@mui/icons-material/AccountTreeOutlined'
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined'
import TuneOutlinedIcon from '@mui/icons-material/TuneOutlined'
import CenterFocusStrongOutlinedIcon from '@mui/icons-material/CenterFocusStrongOutlined'
import HubOutlinedIcon from '@mui/icons-material/HubOutlined'
import AssignmentTurnedInOutlinedIcon from '@mui/icons-material/AssignmentTurnedInOutlined'
import HandshakeOutlinedIcon from '@mui/icons-material/HandshakeOutlined'
import ApartmentOutlinedIcon from '@mui/icons-material/ApartmentOutlined'
import ShieldOutlinedIcon from '@mui/icons-material/ShieldOutlined'
import HistoryOutlinedIcon from '@mui/icons-material/HistoryOutlined'
import AutorenewOutlinedIcon from '@mui/icons-material/AutorenewOutlined'
import MonitorHeartOutlinedIcon from '@mui/icons-material/MonitorHeartOutlined'
import VpnKeyOutlinedIcon from '@mui/icons-material/VpnKeyOutlined'
import CloseIcon from '@mui/icons-material/Close'
import ContentCopyIcon from '@mui/icons-material/ContentCopy'
import CheckIcon from '@mui/icons-material/Check'
import { useQuery } from '@tanstack/react-query'
import { Navigate } from 'react-router-dom'
import { api } from '../api/client'
import { useCurrentUser } from './useAuth'
import { Brand } from '../components/Brand'
import { MONO } from '../theme'

// The BFF endpoint that begins the OIDC authorization-code flow. A full-page navigation (not fetch) —
// the response is a 302 to Cognito's hosted login on another origin.
const COGNITO_LOGIN_URL = '/oauth2/authorization/cognito'

/**
 * The front page is a bespoke, ALWAYS-DARK "technical" surface (mode-independent by design, like the
 * old Constellation hero) — deep near-black navy + a faint engineering grid + teal/indigo accents.
 * These explicit tokens are used throughout so the page renders identically regardless of the app's
 * light/dark setting. (The rest of the app stays theme-aware.)
 */
const DK = {
  bg: '#05070e',
  surface: '#0b1120',
  border: 'rgba(148,163,214,0.14)',
  borderStrong: 'rgba(148,163,214,0.22)',
  text: '#eaf0ff',
  muted: '#8a97b8',
  teal: '#2dd4bf',
  tealBright: '#5eead4',
  indigo: '#818cf8',
} as const

const HEADING_FONT = '"Space Grotesk", sans-serif'

type RoleKey = 'patient' | 'provider' | 'provider2' | 'coordinator' | 'reviewer' | 'admin' | 'auditor'

/**
 * The six role personas shown in the landing header (icon + label), clickable to open the Credentials
 * dialog with that role's demo account highlighted.
 */
const ROLE_NAV: { key: RoleKey; label: string; icon: SvgIconComponent }[] = [
  { key: 'patient', label: 'Patient', icon: PersonOutlinedIcon },
  { key: 'provider', label: 'Provider', icon: MedicalServicesOutlinedIcon },
  { key: 'coordinator', label: 'Care Coordinator', icon: AccountTreeOutlinedIcon },
  { key: 'reviewer', label: 'Reviewer', icon: FactCheckOutlinedIcon },
  { key: 'admin', label: 'Admin', icon: TuneOutlinedIcon },
  { key: 'auditor', label: 'Auditor', icon: CenterFocusStrongOutlinedIcon },
]

/**
 * The "What HealthCloud brings together" bento grid — each tile is a real, backend-enforced capability
 * (honest capabilities, not fake telemetry — rule 2). `span` = a wide (2-col) tile on desktop; `hero`
 * = the featured Coordination tile. `hue` tints the tile bg/border/icon on the dark surface.
 */
const FEATURES: {
  key: string
  title: string
  subtitle: string
  icon: SvgIconComponent
  hue: string
  span: boolean
  hero?: boolean
}[] = [
  { key: 'coordination', title: 'Coordination', subtitle: 'Connected care workflows', icon: HubOutlinedIcon, hue: '#2dd4bf', span: true, hero: true },
  { key: 'adjudication', title: 'Adjudication', subtitle: 'Explainable claims decisions', icon: AssignmentTurnedInOutlinedIcon, hue: '#34d399', span: true },
  { key: 'consent', title: 'Consent', subtitle: 'Patient-controlled permissions', icon: HandshakeOutlinedIcon, hue: '#a78bfa', span: false },
  { key: 'isolation', title: 'Isolation', subtitle: 'Separate organizational data', icon: ApartmentOutlinedIcon, hue: '#60a5fa', span: false },
  { key: 'security', title: 'Security', subtitle: 'Layered access protection', icon: ShieldOutlinedIcon, hue: '#2dd4bf', span: false },
  { key: 'traceability', title: 'Traceability', subtitle: 'Accountable decision history', icon: HistoryOutlinedIcon, hue: '#fbbf24', span: false },
  { key: 'resilience', title: 'Resilience', subtitle: 'Failure handling and recovery', icon: AutorenewOutlinedIcon, hue: '#f87171', span: true },
  { key: 'observability', title: 'Observability', subtitle: 'Logs, metrics, and traces', icon: MonitorHeartOutlinedIcon, hue: '#818cf8', span: true },
]

/**
 * The role rows shown in each org column of the Credentials dialog. Each role has ONE color, reused
 * across both organizations (so "Patient" is the same color for NorthCare and Green Valley, etc.).
 * `provider2` is a second provider account and deliberately shares the Provider color → 6 colors total.
 */
const ROLE_ROWS: { key: RoleKey; label: string; note: string; color: string }[] = [
  { key: 'patient', label: 'Patient', note: 'Sees only their own record & consent', color: '#0d9488' },
  { key: 'provider', label: 'Provider', note: 'Sees only patients assigned to them', color: '#4f46e5' },
  { key: 'provider2', label: 'Provider (2nd)', note: 'A second provider — unassigned by default', color: '#4f46e5' },
  { key: 'coordinator', label: 'Care Coordinator', note: 'Assign care teams; manage requests & eligibility', color: '#c026d3' },
  { key: 'reviewer', label: 'Claims Reviewer', note: 'Accept & adjudicate claims', color: '#b45309' },
  { key: 'admin', label: 'Org Admin', note: 'Full tenant view — manage plans, users, everything', color: '#2563eb' },
  { key: 'auditor', label: 'Auditor', note: 'Read the tamper-evident audit trail', color: '#e11d48' },
]

// The Credentials popup is deliberately LIGHT (on the dark page) so the demo credentials — the project's
// differentiator — stand out. These are its light-surface tokens; the role colors above are chosen to read
// on this light surface.
const LT = {
  paper: '#ffffff',
  surface: '#f8fafc',
  border: '#e6e9f0',
  text: '#0f1729',
  muted: '#5b6576',
  chipBg: '#eef2f7',
} as const

/** A one-click copy-to-clipboard icon button (email / password), with a brief "copied" check state. */
function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <IconButton
      size="small"
      aria-label={`Copy ${label}`}
      onClick={() => {
        navigator.clipboard?.writeText(value)
        setCopied(true)
        setTimeout(() => setCopied(false), 1200)
      }}
      sx={{ p: 0.4, color: copied ? '#0d9488' : '#94a3b8', '&:hover': { color: '#0d9488', bgcolor: 'rgba(13,148,136,0.08)' } }}
    >
      {copied ? <CheckIcon sx={{ fontSize: 15 }} /> : <ContentCopyIcon sx={{ fontSize: 15 }} />}
    </IconButton>
  )
}

/**
 * The two synthetic demo organizations. Each account's email is `${role.key}@${domain}`; passwords are
 * the shared synthetic demo passwords (intentionally public so reviewers can self-serve). Synthetic data
 * only — these accounts reach no real data and are tenant-isolated from each other.
 */
const ORGS: { name: string; domain: string; passwords: Record<RoleKey, string> }[] = [
  {
    name: 'NorthCare Clinic',
    domain: 'northcare.example.org',
    passwords: {
      patient: 'Samnorthcare123',
      provider: 'Northcare123',
      provider2: 'Providernc123',
      coordinator: 'Coordinatornc123',
      reviewer: 'Reviewernc123',
      admin: 'Adminnc123',
      auditor: 'Auditornc123',
    },
  },
  {
    name: 'Green Valley Clinic',
    domain: 'greenvalley.example.org',
    passwords: {
      patient: 'Samgv123',
      provider: 'Greenvalley123',
      provider2: 'Providergv123',
      coordinator: 'Coordinatorgv123',
      reviewer: 'Reviewergv123',
      admin: 'Admingc123',
      auditor: 'Auditorgv123',
    },
  },
]

export function LoginPage() {
  const { data: user } = useCurrentUser()
  const [highlight, setHighlight] = useState<RoleKey | null>(null)
  const [credentialsOpen, setCredentialsOpen] = useState(false)

  // Is the Cognito flow actually configured in this environment? (Deployed: yes; local without the
  // `cognito` profile: no.) Drives whether the "Sign in" button is live or disabled.
  const authConfig = useQuery({
    queryKey: ['auth-config'],
    queryFn: () => api.authConfig(),
    staleTime: Infinity,
    retry: false,
  })
  const cognitoEnabled = authConfig.data?.cognitoEnabled ?? true

  if (user) {
    return <Navigate to="/" replace />
  }

  // Open the demo-credentials dialog — from the "Credentials" button (no role) or a role persona
  // (which highlights that role's account).
  const openCredentials = (role?: RoleKey) => {
    setHighlight(role ?? null)
    setCredentialsOpen(true)
  }

  // The clickable role personas — rendered inline on desktop, and on a wrapped second row on
  // tablet/phone (so they're never hidden). A function so both placements get fresh elements.
  const rolePersonas = () =>
    ROLE_NAV.map((r) => {
      const Icon = r.icon
      const active = highlight === r.key
      return (
        <Box
          key={r.key}
          component="button"
          type="button"
          onClick={() => openCredentials(r.key)}
          sx={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 0.75,
            background: 'none',
            border: 0,
            p: 0,
            cursor: 'pointer',
            color: active ? DK.tealBright : DK.muted,
            transition: 'color .15s',
            '&:hover': { color: DK.tealBright },
          }}
        >
          <Icon sx={{ fontSize: 24 }} />
          <Box component="span" sx={{ fontSize: '0.9rem', fontWeight: 500, whiteSpace: 'nowrap' }}>
            {r.label}
          </Box>
        </Box>
      )
    })

  return (
    <Box
      sx={{
        minHeight: '100vh',
        color: DK.text,
        backgroundColor: DK.bg,
        // Soft "aurora" glow wash (no grid): a teal glow up top, an indigo glow off the top-right, and a
        // faint teal pool bottom-left — layered radial gradients for depth on the deep-navy field.
        backgroundImage: `radial-gradient(1100px 620px at 50% -14%, rgba(45,212,191,0.20), transparent 58%),
          radial-gradient(900px 520px at 100% 4%, rgba(129,140,248,0.16), transparent 55%),
          radial-gradient(820px 560px at 2% 100%, rgba(45,212,191,0.08), transparent 55%)`,
        backgroundRepeat: 'no-repeat',
      }}
    >
      {/* ── Header: brand · role nav · Sign in ─────────────────────────────────────────── */}
      <Box component="header" sx={{ borderBottom: 1, borderColor: DK.border }}>
        <Container maxWidth="lg" sx={{ py: { xs: 2, md: 2.75 } }}>
          {/* Top row: brand · (roles inline on desktop) · Sign in */}
          <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 2 }}>
            <Brand size="lg" onDark />

            {/* Role personas inline — desktop (md+). On smaller screens they move to the row below.
                Natural width + a uniform gap keeps the whitespace between every pair equal and compact. */}
            <Stack
              direction="row"
              sx={{ display: { xs: 'none', md: 'flex' }, alignItems: 'flex-start', gap: { md: 2.5, lg: 4 } }}
            >
              {rolePersonas()}
            </Stack>

            {/* The single sign-in action for the site: a full-page navigation to the Cognito BFF flow
                (disabled only when Cognito isn't configured in this environment). */}
            <Button
              variant="outlined"
              component={cognitoEnabled ? 'a' : 'button'}
              href={cognitoEnabled ? COGNITO_LOGIN_URL : undefined}
              disabled={!cognitoEnabled}
              sx={{
                borderRadius: 999,
                px: { xs: 2.5, sm: 3 },
                py: 1,
                fontSize: '1rem',
                flexShrink: 0,
                color: DK.text,
                borderColor: 'rgba(45,212,191,0.55)',
                borderWidth: 1.5,
                '&:hover': { borderColor: DK.teal, borderWidth: 1.5, backgroundColor: 'rgba(45,212,191,0.10)' },
                '&.Mui-disabled': { color: DK.muted, borderColor: DK.border },
              }}
            >
              Sign in
            </Button>
          </Stack>

          {/* Role personas — second row on tablet/phone (below md), centered + wrapped so all six show. */}
          <Box
            sx={{
              display: { xs: 'flex', md: 'none' },
              justifyContent: 'center',
              flexWrap: 'wrap',
              rowGap: 2,
              columnGap: { xs: 2.5, sm: 4 },
              mt: 2.25,
            }}
          >
            {rolePersonas()}
          </Box>
        </Container>
      </Box>

      {/* ── Hero ───────────────────────────────────────────────────────────────────────── */}
      <Container maxWidth="lg" sx={{ pt: { xs: 7, md: 12 }, pb: { xs: 6, md: 9 } }}>
        <Typography
          component="h1"
          sx={{
            fontFamily: HEADING_FONT,
            fontWeight: 700,
            fontSize: { xs: '2.1rem', sm: '2.9rem', md: '3.7rem' },
            lineHeight: 1.1,
            letterSpacing: '-0.02em',
            textAlign: 'center',
            color: DK.text,
            maxWidth: 1060,
            mx: 'auto',
          }}
        >
          Care <Box component="span" sx={{ color: DK.teal }}>coordinated.</Box>{' '}
          Consent <Box component="span" sx={{ color: DK.teal }}>enforced.</Box>{' '}
          Decisions <Box component="span" sx={{ color: DK.teal }}>explained.</Box>
        </Typography>

        <Typography
          sx={{
            textAlign: 'center',
            color: DK.muted,
            fontSize: { xs: '1.05rem', md: '1.32rem' },
            lineHeight: 1.6,
            maxWidth: 1100,
            mx: 'auto',
            mt: { xs: 3, md: 4.5 },
          }}
        >
          HealthCloud brings care coordination and synthetic claims processing into one platform, with access
          governed by patient relationships, consent, purpose, and field-level policies.
        </Typography>
      </Container>

      {/* ── "What HealthCloud brings together" — the bento feature grid ──────────────────── */}
      <Container maxWidth="lg" sx={{ pb: { xs: 8, md: 12 } }}>
        {/* Section header: eyebrow + Credentials button */}
        <Stack
          direction="row"
          sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 2, flexWrap: 'wrap', mb: { xs: 2.5, md: 3.5 } }}
        >
          <Typography
            sx={{
              fontSize: '0.8rem',
              fontWeight: 600,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              color: DK.muted,
            }}
          >
            What HealthCloud brings together.
          </Typography>

          {/* Single invitation button — reads "Explore HealthCloud in action  🔑 Credentials"; opens the dialog. */}
          <Button
            variant="outlined"
            onClick={() => openCredentials()}
            sx={{
              borderRadius: 999,
              px: 2.75,
              py: 0.9,
              fontSize: '0.95rem',
              flexShrink: 0,
              color: DK.text,
              borderColor: DK.borderStrong,
              '&:hover': { borderColor: DK.teal, backgroundColor: 'rgba(45,212,191,0.10)' },
            }}
          >
            Explore HealthCloud in action
            <Box
              component="span"
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.6,
                ml: 1.5,
                pl: 1.5,
                borderLeft: `1px solid ${DK.borderStrong}`,
                color: DK.tealBright,
                fontWeight: 700,
              }}
            >
              <VpnKeyOutlinedIcon sx={{ fontSize: 18 }} />
              Credentials
            </Box>
          </Button>
        </Stack>

        {/* Bento grid: 4 cols on desktop (wide tiles span 2), 2 on tablet, 1 on phone. */}
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)' },
            gap: { xs: 1.75, md: 2.25 },
          }}
        >
          {FEATURES.map((f) => {
            const Icon = f.icon
            return (
              <Box
                key={f.key}
                sx={{
                  gridColumn: f.span ? { sm: 'span 2' } : 'auto',
                  minHeight: f.span ? { xs: 150, md: 210 } : { xs: 130, md: 156 },
                  p: { xs: 2.5, md: 3 },
                  borderRadius: 4,
                  border: 1,
                  display: 'flex',
                  flexDirection: 'column',
                  ...(f.hero
                    ? {
                        borderColor: 'rgba(45,212,191,0.32)',
                        backgroundColor: DK.surface,
                        backgroundImage:
                          'linear-gradient(135deg, rgba(45,212,191,0.16), transparent 55%), radial-gradient(120% 120% at 0% 0%, rgba(129,140,248,0.12), transparent 60%)',
                      }
                    : {
                        borderColor: `${f.hue}3d`,
                        backgroundColor: `${f.hue}1f`,
                      }),
                }}
              >
                <Icon sx={{ fontSize: 26, color: f.hue }} />
                <Typography
                  sx={{
                    fontFamily: HEADING_FONT,
                    fontWeight: 600,
                    fontSize: { xs: '1.3rem', md: '1.6rem' },
                    letterSpacing: '-0.01em',
                    mt: { xs: 1.5, md: 2 },
                    color: DK.text,
                  }}
                >
                  {f.title}
                </Typography>
                <Typography sx={{ mt: 0.5, fontSize: '0.95rem', lineHeight: 1.45, color: DK.muted }}>
                  {f.subtitle}
                </Typography>
              </Box>
            )
          })}
        </Box>

        {/* Footer strip */}
        <Stack
          direction="row"
          sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 2, flexWrap: 'wrap', mt: { xs: 2.5, md: 3 } }}
        >
          <Typography sx={{ fontSize: '0.85rem', color: DK.muted }}>
            Explore the demo through six different roles.
          </Typography>
          <Typography sx={{ fontSize: '0.85rem', color: DK.muted }}>Synthetic demo data</Typography>
        </Stack>
      </Container>

      {/* ── Credentials dialog (opened by the "Credentials" button or a role persona) ─────── */}
      <Dialog
        open={credentialsOpen}
        onClose={() => setCredentialsOpen(false)}
        maxWidth="lg"
        fullWidth
        slotProps={{
          paper: {
            sx: {
              backgroundColor: LT.paper,
              backgroundImage: 'none',
              color: LT.text,
              border: `1px solid ${LT.border}`,
              borderRadius: 3,
            },
          },
        }}
      >
        <DialogContent sx={{ p: { xs: 3, md: 4 } }}>
          <IconButton
            aria-label="Close"
            onClick={() => setCredentialsOpen(false)}
            sx={{ position: 'absolute', top: 12, right: 12, color: LT.muted }}
          >
            <CloseIcon />
          </IconButton>

          <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap', gap: 1, alignItems: 'center', mb: 0.5, pr: 4 }}>
            <Typography component="h2" sx={{ fontFamily: HEADING_FONT, fontWeight: 700, fontSize: '1.2rem', color: LT.text }}>
              👋 Reviewing this project? Explore the live demo
            </Typography>
            <Box
              component="span"
              sx={{
                px: 1.1,
                py: 0.35,
                borderRadius: 999,
                fontSize: '0.7rem',
                fontWeight: 700,
                letterSpacing: '0.02em',
                color: '#0f766e',
                bgcolor: 'rgba(13,148,136,0.12)',
                border: '1px solid rgba(13,148,136,0.35)',
              }}
            >
              Synthetic data only
            </Box>
          </Stack>
          <Typography sx={{ mb: 3, fontSize: '0.9rem', color: LT.muted }}>
            Click{' '}
            <Box component="strong" sx={{ color: LT.text }}>
              Sign in
            </Box>{' '}
            at the top right, then use any account below — each account has its own password (tap the copy
            icons). Each role is the same color across both organizations.
          </Typography>

          {/* Two organization columns — NorthCare (left) · Green Valley (right); roles color-matched. */}
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: { xs: 3, md: 4 } }}>
            {ORGS.map((org) => (
              <Box key={org.domain}>
                <Typography sx={{ fontFamily: HEADING_FONT, fontWeight: 700, fontSize: '1.05rem', color: LT.text, mb: 1.5 }}>
                  {org.name}
                </Typography>
                <Stack spacing={1.25}>
                  {ROLE_ROWS.map((role) => {
                    const active = highlight === role.key
                    const email = `${role.key}@${org.domain}`
                    return (
                      <Box
                        key={role.key}
                        sx={{
                          p: 1.5,
                          borderRadius: '12px',
                          border: `1px solid ${active ? role.color : LT.border}`,
                          backgroundColor: active ? `${role.color}0f` : LT.surface,
                          transition: 'border-color .15s, background-color .15s',
                        }}
                      >
                        <Box
                          component="span"
                          sx={{
                            display: 'inline-block',
                            mb: 0.75,
                            px: 1,
                            py: 0.3,
                            borderRadius: 999,
                            fontSize: '0.68rem',
                            fontWeight: 700,
                            letterSpacing: '0.02em',
                            color: role.color,
                            bgcolor: `${role.color}1a`,
                            border: `1px solid ${role.color}55`,
                          }}
                        >
                          {role.label}
                        </Box>

                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, flexWrap: 'wrap' }}>
                          <Typography sx={{ fontFamily: MONO, fontSize: '0.8rem', fontWeight: 700, color: LT.text, wordBreak: 'break-all' }}>
                            {email}
                          </Typography>
                          <CopyButton value={email} label="email" />
                        </Box>

                        <Box sx={{ mt: 0.5, display: 'flex', alignItems: 'center', gap: 0.5, flexWrap: 'wrap' }}>
                          <Box component="span" sx={{ fontSize: '0.7rem', color: LT.muted }}>
                            Password
                          </Box>
                          <Box
                            component="code"
                            sx={{
                              fontFamily: MONO,
                              fontSize: '0.78rem',
                              fontWeight: 600,
                              color: LT.text,
                              px: 0.9,
                              py: 0.3,
                              borderRadius: '6px',
                              bgcolor: LT.chipBg,
                              border: `1px solid ${LT.border}`,
                              wordBreak: 'break-all',
                            }}
                          >
                            {org.passwords[role.key]}
                          </Box>
                          <CopyButton value={org.passwords[role.key]} label="password" />
                        </Box>

                        <Typography sx={{ mt: 0.6, fontSize: '0.72rem', color: LT.muted, lineHeight: 1.4 }}>
                          {role.note}
                        </Typography>
                      </Box>
                    )
                  })}
                </Stack>
              </Box>
            ))}
          </Box>

          <Divider sx={{ my: 3, borderColor: LT.border }} />
          <Stack spacing={1.25}>
            <Typography sx={{ fontSize: '0.85rem', color: LT.muted, lineHeight: 1.55 }}>
              🏥{' '}
              <Box component="strong" sx={{ color: LT.text }}>
                See multi-tenant isolation:
              </Box>{' '}
              NorthCare (left) and Green Valley (right) are two separate organizations — sign in to each and
              notice a NorthCare user can never see Green Valley’s data.
            </Typography>
            <Typography sx={{ fontSize: '0.85rem', color: LT.muted, lineHeight: 1.55 }}>
              🔄{' '}
              <Box component="strong" sx={{ color: LT.text }}>
                To switch roles:
              </Box>{' '}
              open a new Incognito window — Cognito remembers your last sign-in, so a fresh window lets you
              log in as someone else.
            </Typography>
          </Stack>
        </DialogContent>
      </Dialog>
    </Box>
  )
}

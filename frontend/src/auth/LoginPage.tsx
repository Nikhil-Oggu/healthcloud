import { useState } from 'react'
import { Box, Button, Card, CardContent, Chip, Container, Divider, Stack, Typography } from '@mui/material'
import type { SvgIconComponent } from '@mui/icons-material'
import PersonOutlinedIcon from '@mui/icons-material/PersonOutlined'
import AccountTreeOutlinedIcon from '@mui/icons-material/AccountTreeOutlined'
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined'
import TuneOutlinedIcon from '@mui/icons-material/TuneOutlined'
import CenterFocusStrongOutlinedIcon from '@mui/icons-material/CenterFocusStrongOutlined'
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
 * Public demo password shown on the login page so a reviewer/recruiter can sign in and explore.
 * Synthetic data only — safe to publish. All demo accounts share ONE password.
 * 👉 EDIT this to match the password you set for the Cognito demo accounts before deploying.
 */
const DEMO_PASSWORD = 'REPLACE_WITH_YOUR_DEMO_PASSWORD'

type RoleKey = 'patient' | 'provider' | 'coordinator' | 'reviewer' | 'admin' | 'auditor'

/**
 * The role personas shown in the landing header (icon + label), clickable to jump to sign-in with that
 * role's demo account highlighted. Mirrors the reference design (5 roles across the top nav).
 */
const ROLE_NAV: { key: RoleKey; label: string; icon: SvgIconComponent }[] = [
  { key: 'patient', label: 'Patient', icon: PersonOutlinedIcon },
  { key: 'coordinator', label: 'Care Coordinator', icon: AccountTreeOutlinedIcon },
  { key: 'reviewer', label: 'Reviewer', icon: FactCheckOutlinedIcon },
  { key: 'admin', label: 'Admin', icon: TuneOutlinedIcon },
  { key: 'auditor', label: 'Auditor', icon: CenterFocusStrongOutlinedIcon },
]

/**
 * The seeded synthetic demo accounts (all NorthCare — swap the org for Green Valley to see isolation).
 * Keyed by RoleKey so a header role click can highlight the matching card. Provider is included here
 * (relationship-gating demo) even though the top nav mirrors the reference's five roles.
 */
const DEMO_ACCOUNTS: { key: RoleKey; role: string; email: string; note: string }[] = [
  { key: 'patient', role: 'Patient', email: 'patient@northcare.example.org', note: 'Sees only their own record & consent' },
  { key: 'provider', role: 'Provider', email: 'provider@northcare.example.org', note: 'Sees only patients assigned to them' },
  { key: 'coordinator', role: 'Care Coordinator', email: 'coordinator@northcare.example.org', note: 'Assign care teams; manage requests & eligibility' },
  { key: 'reviewer', role: 'Claims Reviewer', email: 'reviewer@northcare.example.org', note: 'Accept & adjudicate claims' },
  { key: 'admin', role: 'Org Admin', email: 'admin@northcare.example.org', note: 'Full tenant view — manage plans, users, everything' },
  { key: 'auditor', role: 'Auditor', email: 'auditor@northcare.example.org', note: 'Read the tamper-evident audit trail' },
]

export function LoginPage() {
  const { data: user } = useCurrentUser()
  const [highlight, setHighlight] = useState<RoleKey | null>(null)

  // Is the Cognito flow actually configured in this environment? (Deployed: yes; local without the
  // `cognito` profile: no.) Drives whether the button is live or shown disabled with an explanation.
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

  const scrollToSignIn = (role?: RoleKey) => {
    if (role) setHighlight(role)
    document.getElementById('signin')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <Box
      sx={(theme) => ({
        minHeight: '100vh',
        // Light = the reference's soft mint→white wash. Dark = a technical deep-navy field: a teal glow
        // up top + a faint engineering grid.
        backgroundColor: '#f7faf9',
        backgroundImage: `radial-gradient(1200px 520px at 50% -12%, rgba(13,148,136,0.12), transparent 62%),
          linear-gradient(180deg, rgba(214,240,235,0.55), transparent 42%)`,
        backgroundRepeat: 'no-repeat',
        ...theme.applyStyles('dark', {
          backgroundColor: '#070b18',
          backgroundImage: `radial-gradient(1200px 540px at 50% -12%, rgba(45,212,191,0.14), transparent 60%),
            linear-gradient(rgba(148,163,214,0.05) 1px, transparent 1px),
            linear-gradient(90deg, rgba(148,163,214,0.05) 1px, transparent 1px)`,
          backgroundSize: 'auto, 44px 44px, 44px 44px',
          backgroundPosition: 'center top, center, center',
        }),
      })}
    >
      {/* ── Header: brand · role nav · Sign in ─────────────────────────────────────────── */}
      <Box component="header" sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Container maxWidth="lg" sx={{ py: { xs: 2, md: 2.75 } }}>
          <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 2 }}>
            <Brand size="lg" />

            {/* Role personas — hidden on phones, where a horizontal role bar doesn't fit. */}
            <Stack
              direction="row"
              sx={{ display: { xs: 'none', md: 'flex' }, alignItems: 'flex-start', gap: { md: 3.5, lg: 5 } }}
            >
              {ROLE_NAV.map((r) => {
                const Icon = r.icon
                const active = highlight === r.key
                return (
                  <Box
                    key={r.key}
                    component="button"
                    type="button"
                    onClick={() => scrollToSignIn(r.key)}
                    sx={{
                      display: 'flex',
                      flexDirection: 'column',
                      alignItems: 'center',
                      gap: 0.75,
                      background: 'none',
                      border: 0,
                      p: 0,
                      cursor: 'pointer',
                      color: active ? 'primary.main' : 'text.secondary',
                      transition: 'color .15s',
                      '&:hover': { color: 'primary.main' },
                    }}
                  >
                    <Icon sx={{ fontSize: 24 }} />
                    <Box component="span" sx={{ fontSize: '0.98rem', fontWeight: 500, whiteSpace: 'nowrap' }}>
                      {r.label}
                    </Box>
                  </Box>
                )
              })}
            </Stack>

            <Button
              variant="outlined"
              onClick={() => scrollToSignIn()}
              sx={{
                borderRadius: 999,
                px: 3,
                py: 1,
                fontSize: '1rem',
                color: 'text.primary',
                borderColor: 'primary.main',
                borderWidth: 1.5,
                '&:hover': { borderColor: 'primary.main', borderWidth: 1.5, backgroundColor: 'action.hover' },
              }}
            >
              Sign in
            </Button>
          </Stack>
        </Container>
      </Box>

      {/* ── Hero ───────────────────────────────────────────────────────────────────────── */}
      <Container maxWidth="lg" sx={{ pt: { xs: 7, md: 12 }, pb: { xs: 6, md: 9 } }}>
        <Typography
          component="h1"
          sx={{
            fontFamily: '"Space Grotesk", sans-serif',
            fontWeight: 700,
            fontSize: { xs: '2.1rem', sm: '2.9rem', md: '3.7rem' },
            lineHeight: 1.1,
            letterSpacing: '-0.02em',
            textAlign: 'center',
            color: 'text.primary',
            maxWidth: 1060,
            mx: 'auto',
          }}
        >
          Care <Box component="span" sx={{ color: 'primary.main' }}>coordinated.</Box>{' '}
          Consent <Box component="span" sx={{ color: 'primary.main' }}>enforced.</Box>{' '}
          Decisions <Box component="span" sx={{ color: 'primary.main' }}>explained.</Box>
        </Typography>

        <Typography
          sx={{
            textAlign: 'center',
            color: 'text.secondary',
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

      {/* ── Sign in (below the hero) ────────────────────────────────────────────────────── */}
      <Container id="signin" maxWidth="md" sx={{ pb: { xs: 8, md: 12 }, scrollMarginTop: 24 }}>
        <Card sx={{ maxWidth: 460, mx: 'auto' }}>
          <CardContent sx={{ p: { xs: 3, md: 4 }, textAlign: 'center' }}>
            <Typography component="h2" sx={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: '1.6rem' }}>
              Sign in
            </Typography>
            <Typography sx={{ mt: 0.75, mb: 3, fontSize: '0.98rem', color: 'text.secondary' }}>
              Access your HealthCloud workspace.
            </Typography>

            <Button
              variant="contained"
              fullWidth
              size="large"
              component={cognitoEnabled ? 'a' : 'button'}
              href={cognitoEnabled ? COGNITO_LOGIN_URL : undefined}
              disabled={!cognitoEnabled}
              sx={{ py: 1.4, fontSize: '1rem' }}
            >
              Sign in with Cognito
              <Box component="span" aria-hidden sx={{ ml: 0.7 }}>↗</Box>
            </Button>
            {!cognitoEnabled && (
              <Typography sx={{ display: 'block', mt: 1.5, fontSize: '0.8rem', color: 'text.secondary' }}>
                Cognito sign-in isn’t configured in this environment.
              </Typography>
            )}
          </CardContent>
        </Card>

        {/* Demo credentials — shown whenever Cognito login is available. Synthetic data only. */}
        {cognitoEnabled && (
          <Card sx={{ mt: { xs: 4, md: 5 } }}>
            <CardContent sx={{ p: { xs: 3, md: 4 } }}>
              <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap', gap: 1, alignItems: 'center', mb: 0.5 }}>
                <Typography component="h2" sx={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: '1.2rem' }}>
                  👋 Reviewing this project? Explore the live demo
                </Typography>
                <Chip size="small" color="success" label="Synthetic data only" />
              </Stack>
              <Typography sx={{ mb: 2.5, fontSize: '0.9rem', color: 'text.secondary' }}>
                Click{' '}
                <Box component="strong" sx={{ color: 'text.primary' }}>
                  Sign in with Cognito
                </Box>{' '}
                above, then use any account below. Every account shares the same password.
              </Typography>

              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1, alignItems: 'center', mb: 3 }}>
                <Typography sx={{ fontSize: '0.9rem', color: 'text.secondary' }}>Password for all accounts:</Typography>
                <Box
                  component="code"
                  sx={{
                    fontFamily: MONO,
                    fontWeight: 600,
                    color: 'primary.main',
                    px: 1.2,
                    py: 0.6,
                    borderRadius: '8px',
                    bgcolor: 'action.hover',
                    border: 1,
                    borderColor: 'divider',
                  }}
                >
                  {DEMO_PASSWORD}
                </Box>
              </Stack>

              <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 2 }}>
                {DEMO_ACCOUNTS.map((account) => {
                  const active = highlight === account.key
                  return (
                    <Box
                      key={account.email}
                      sx={{
                        p: 2,
                        border: active ? 2 : 1,
                        borderColor: active ? 'primary.main' : 'divider',
                        borderRadius: '12px',
                        transition: 'border-color .15s',
                      }}
                    >
                      <Chip size="small" color={active ? 'primary' : 'default'} label={account.role} sx={{ mb: 1 }} />
                      <Typography sx={{ fontFamily: MONO, fontSize: '0.82rem', color: 'primary.main', wordBreak: 'break-all' }}>
                        {account.email}
                      </Typography>
                      <Typography sx={{ display: 'block', mt: 0.5, fontSize: '0.75rem', color: 'text.secondary' }}>
                        {account.note}
                      </Typography>
                    </Box>
                  )
                })}
              </Box>

              <Divider sx={{ my: 3 }} />
              <Stack spacing={1.25}>
                <Typography sx={{ fontSize: '0.85rem', color: 'text.secondary', lineHeight: 1.55 }}>
                  🏥{' '}
                  <Box component="strong" sx={{ color: 'text.primary' }}>
                    See multi-tenant isolation:
                  </Box>{' '}
                  every role also exists for a second organization — swap <code>northcare</code> for{' '}
                  <code>greenvalley</code> (e.g.{' '}
                  <Box component="code" sx={{ fontFamily: MONO, color: 'primary.main' }}>
                    provider@greenvalley.example.org
                  </Box>
                  ) and notice a NorthCare user can never see Green Valley’s data.
                </Typography>
                <Typography sx={{ fontSize: '0.85rem', color: 'text.secondary', lineHeight: 1.55 }}>
                  🔄{' '}
                  <Box component="strong" sx={{ color: 'text.primary' }}>
                    To switch roles:
                  </Box>{' '}
                  open a new Incognito window — Cognito remembers your last sign-in, so a fresh window lets you
                  log in as someone else.
                </Typography>
              </Stack>
            </CardContent>
          </Card>
        )}
      </Container>
    </Box>
  )
}

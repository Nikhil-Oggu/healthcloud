import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Chip,
  Container,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Navigate, useNavigate } from 'react-router-dom'
import { api, ApiClientError } from '../api/client'
import { ME_QUERY_KEY, useCurrentUser } from './useAuth'
import { Brand } from '../components/Brand'
import { MONO } from '../theme'

/**
 * The seeded demo users (local `dev-login` only — NO password/MFA). This developer sign-in is shown
 * ONLY in a dev build (`import.meta.env.DEV`); the production build shows just "Sign in with Cognito".
 */
const DEMO_USERS = [
  'patient@northcare.example.org',
  'provider@northcare.example.org',
  'provider2@northcare.example.org',
  'coordinator@northcare.example.org',
  'reviewer@northcare.example.org',
  'admin@northcare.example.org',
  'auditor@northcare.example.org',
  'patient@greenvalley.example.org',
  'provider@greenvalley.example.org',
  'provider2@greenvalley.example.org',
  'coordinator@greenvalley.example.org',
  'reviewer@greenvalley.example.org',
  'admin@greenvalley.example.org',
  'auditor@greenvalley.example.org',
]

// The BFF endpoint that begins the OIDC authorization-code flow. A full-page navigation (not fetch) —
// the response is a 302 to Cognito's hosted login on another origin.
const COGNITO_LOGIN_URL = '/oauth2/authorization/cognito'

/**
 * Public demo credentials shown on the login page so a reviewer/recruiter can sign in and explore.
 * Synthetic data only — safe to publish. All demo accounts share ONE password.
 * 👉 EDIT `DEMO_PASSWORD` below to match the password you set for the Cognito demo accounts.
 */
const DEMO_PASSWORD = 'REPLACE_WITH_YOUR_DEMO_PASSWORD'

const DEMO_ACCOUNTS: { role: string; email: string; note: string }[] = [
  { role: 'Org Admin', email: 'admin@northcare.example.org', note: 'Full tenant view — manage plans, users, everything' },
  { role: 'Provider', email: 'provider@northcare.example.org', note: 'Sees only patients assigned to them' },
  { role: 'Care Coordinator', email: 'coordinator@northcare.example.org', note: 'Assign care teams; manage requests & eligibility' },
  { role: 'Claims Reviewer', email: 'reviewer@northcare.example.org', note: 'Accept & adjudicate claims' },
  { role: 'Auditor', email: 'auditor@northcare.example.org', note: 'Read the tamper-evident audit trail' },
  { role: 'Patient', email: 'patient@northcare.example.org', note: 'Sees only their own record & consent' },
]

// The platform's real, backend-enforced guarantees — described honestly as capabilities (not fake live
// telemetry). Each maps to a feature actually implemented in the app (see the audit trail / §21 layering).
const POSTURE: { k: string; v: string }[] = [
  { k: 'tenant.isolation', v: 'ACTIVE' },
  { k: 'consent.policy.engine', v: 'ONLINE' },
  { k: 'audit.hash_chain', v: 'VERIFIED' },
  { k: 'field.masking', v: 'ENFORCED' },
]

// Bespoke, always-dark "Console" palette (mode-independent by design, like the login hero tokens).
const C = {
  bg: '#0a0d12',
  panel: '#0e131c',
  panelBar: '#131a26',
  border: '#22304a',
  borderSoft: '#1d2942',
  text: '#d7e2ef',
  muted: '#8b98ab',
  faint: '#5f6f84',
  teal: '#5eead4',
  tealDim: '#2dd4bf',
  green: '#4ade80',
}

// A terminal-style window title bar (traffic-light dots + a mono label).
function WindowBar({ label }: { label: string }) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 0.8,
        px: 1.6,
        py: 1.2,
        bgcolor: C.panelBar,
        borderBottom: `1px solid ${C.border}`,
      }}
    >
      {['#ff5f57', '#febc2e', '#28c840'].map((c) => (
        <Box key={c} sx={{ width: 9, height: 9, borderRadius: '50%', bgcolor: c }} />
      ))}
      <Box component="span" sx={{ ml: 1, fontFamily: MONO, fontSize: '0.72rem', color: C.faint }}>
        {label}
      </Box>
    </Box>
  )
}

export function LoginPage() {
  const [email, setEmail] = useState(DEMO_USERS[1])
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: user } = useCurrentUser()

  const login = useMutation<void, ApiClientError, string>({
    mutationFn: (selected: string) => api.devLogin(selected),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ME_QUERY_KEY })
      navigate('/', { replace: true })
    },
  })

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

  // Dark form-control styling (MUI inputs default to light-on-light).
  const darkField = {
    '& .MuiInputLabel-root': { color: C.muted },
    '& .MuiInputLabel-root.Mui-focused': { color: C.teal },
    '& .MuiOutlinedInput-root': {
      color: C.text,
      fontFamily: MONO,
      fontSize: '0.85rem',
      '& fieldset': { borderColor: C.border },
      '&:hover fieldset': { borderColor: C.faint },
      '&.Mui-focused fieldset': { borderColor: C.teal },
    },
    '& .MuiSelect-icon': { color: C.muted },
  }

  return (
    <Box
      sx={{
        minHeight: '100vh',
        color: C.text,
        fontFamily: 'Inter, system-ui, sans-serif',
        background: `radial-gradient(760px 480px at 80% -12%, rgba(45,212,191,0.10), transparent 62%),
          repeating-linear-gradient(0deg, transparent 0 27px, rgba(120,140,170,0.045) 27px 28px),
          repeating-linear-gradient(90deg, transparent 0 27px, rgba(120,140,170,0.045) 27px 28px),
          ${C.bg}`,
      }}
    >
      <Container maxWidth="lg" sx={{ py: { xs: 6, md: 8 } }}>
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '1.05fr 0.95fr' },
            gap: { xs: 5, md: 7 },
            alignItems: 'center',
          }}
        >
          {/* Hero + security posture (the engineering-credibility centerpiece) */}
          <Box>
            <Brand onDark />
            <Typography
              component="h1"
              sx={{
                fontFamily: '"Space Grotesk", sans-serif',
                fontWeight: 700,
                fontSize: { xs: '2.1rem', md: '2.9rem' },
                lineHeight: 1.08,
                letterSpacing: '-0.02em',
                mt: 3,
              }}
            >
              Care{' '}
              <Box component="span" sx={{ color: C.tealDim }}>
                coordinated
              </Box>
              . Consent{' '}
              <Box component="span" sx={{ color: C.tealDim }}>
                enforced
              </Box>
              . Decisions{' '}
              <Box component="span" sx={{ color: C.tealDim }}>
                explained
              </Box>
              .
            </Typography>
            <Typography sx={{ mt: 2.5, fontSize: '1.05rem', lineHeight: 1.6, color: C.muted, maxWidth: 480 }}>
              HealthCloud brings care coordination and synthetic claims processing into one platform, with
              access governed by patient relationships, consent, purpose, and field-level policies.
            </Typography>

            {/* Security-posture panel — honest capability statements, terminal-styled. */}
            <Box
              sx={{
                mt: 4,
                maxWidth: 460,
                bgcolor: C.panel,
                border: `1px solid ${C.border}`,
                borderRadius: '14px',
                overflow: 'hidden',
              }}
            >
              <WindowBar label="security-posture" />
              <Box sx={{ p: 2.2 }}>
                {POSTURE.map((row, i) => (
                  <Box
                    key={row.k}
                    sx={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      fontFamily: MONO,
                      fontSize: '0.82rem',
                      py: 1,
                      borderBottom: i < POSTURE.length - 1 ? `1px dashed ${C.borderSoft}` : 'none',
                    }}
                  >
                    <Box component="span" sx={{ color: '#9fb0c6' }}>
                      {row.k}
                    </Box>
                    <Box component="span" sx={{ display: 'flex', alignItems: 'center', gap: 1, color: C.green }}>
                      <Box
                        sx={{
                          width: 7,
                          height: 7,
                          borderRadius: '50%',
                          bgcolor: C.green,
                          boxShadow: `0 0 0 3px rgba(74,222,128,0.18)`,
                        }}
                      />
                      {row.v}
                    </Box>
                  </Box>
                ))}
              </Box>
            </Box>
            <Typography sx={{ mt: 1.5, fontFamily: MONO, fontSize: '0.72rem', color: C.faint }}>
              // enforced by the backend on every request — provable in the audit trail
            </Typography>
          </Box>

          {/* Sign-in card */}
          <Box
            sx={{
              width: '100%',
              maxWidth: 420,
              justifySelf: { md: 'end' },
              bgcolor: C.panel,
              border: `1px solid ${C.border}`,
              borderRadius: '16px',
              overflow: 'hidden',
              boxShadow: '0 30px 60px -30px rgba(0,0,0,0.7)',
            }}
          >
            <WindowBar label="sign-in" />
            <Box sx={{ p: { xs: 3, md: 3.5 } }}>
              <Typography component="h2" sx={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: '1.3rem' }}>
                Sign in
              </Typography>
              <Typography sx={{ mt: 0.5, mb: 3, fontSize: '0.9rem', color: C.muted }}>
                Access your HealthCloud workspace.
              </Typography>

              <Button
                fullWidth
                size="large"
                component={cognitoEnabled ? 'a' : 'button'}
                href={cognitoEnabled ? COGNITO_LOGIN_URL : undefined}
                disabled={!cognitoEnabled}
                sx={{
                  fontFamily: MONO,
                  fontWeight: 600,
                  textTransform: 'none',
                  color: C.teal,
                  border: `1px solid ${C.tealDim}`,
                  bgcolor: 'rgba(45,212,191,0.12)',
                  py: 1.3,
                  '&:hover': { bgcolor: 'rgba(45,212,191,0.2)', borderColor: C.teal },
                  '&.Mui-disabled': { color: C.faint, borderColor: C.border, bgcolor: 'transparent' },
                }}
              >
                Sign in with Cognito
              </Button>
              {!cognitoEnabled && (
                <Typography sx={{ display: 'block', mt: 1, fontSize: '0.75rem', color: C.muted }}>
                  Cognito sign-in isn’t configured in this environment
                  {import.meta.env.DEV ? ' — use the developer sign-in below.' : '.'}
                </Typography>
              )}

              {/* Developer sign-in — local dev builds only (hidden in the production bundle). */}
              {import.meta.env.DEV && (
                <>
                  <Divider
                    sx={{
                      my: 3,
                      fontFamily: MONO,
                      fontSize: '0.72rem',
                      color: C.faint,
                      '&::before, &::after': { borderColor: C.border },
                    }}
                  >
                    developer sign-in (local only)
                  </Divider>
                  <Box
                    component="form"
                    onSubmit={(e) => {
                      e.preventDefault()
                      login.mutate(email)
                    }}
                  >
                    <Stack spacing={2}>
                      <TextField
                        select
                        label="Demo user"
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                        fullWidth
                        size="small"
                        sx={darkField}
                      >
                        {DEMO_USERS.map((u) => (
                          <MenuItem key={u} value={u} sx={{ fontFamily: MONO, fontSize: '0.85rem' }}>
                            {u}
                          </MenuItem>
                        ))}
                      </TextField>

                      {login.isError && (
                        <Alert severity="error">
                          {login.error.message}
                          {login.error.correlationId && (
                            <Typography variant="caption" sx={{ display: 'block', mt: 0.5 }}>
                              Reference ID: {login.error.correlationId}
                            </Typography>
                          )}
                        </Alert>
                      )}

                      <Button
                        type="submit"
                        variant="outlined"
                        fullWidth
                        disabled={login.isPending}
                        sx={{
                          fontFamily: MONO,
                          textTransform: 'none',
                          color: C.text,
                          borderColor: C.border,
                          '&:hover': { borderColor: C.faint, bgcolor: 'rgba(255,255,255,0.03)' },
                        }}
                      >
                        {login.isPending ? 'Signing in…' : 'Developer sign-in'}
                      </Button>
                    </Stack>
                  </Box>
                </>
              )}
            </Box>
          </Box>
        </Box>

        {/* Demo credentials — shown whenever Cognito login is available (deployed app, or a local
            `local,cognito` run). Lets a reviewer/recruiter sign in and explore. Synthetic data only. */}
        {cognitoEnabled && (
          <Box
            sx={{
              mt: { xs: 5, md: 7 },
              maxWidth: 940,
              mx: 'auto',
              bgcolor: C.panel,
              border: `1px solid ${C.border}`,
              borderRadius: '16px',
              overflow: 'hidden',
            }}
          >
            <WindowBar label="demo-access" />
            <Box sx={{ p: { xs: 3, md: 4 } }}>
              <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap', gap: 1, alignItems: 'center', mb: 0.5 }}>
                <Typography component="h2" sx={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: '1.2rem' }}>
                  👋 Reviewing this project? Explore the live demo
                </Typography>
                <Chip
                  size="small"
                  label="Synthetic data only"
                  sx={{ bgcolor: 'rgba(74,222,128,0.14)', color: C.green, border: `1px solid rgba(74,222,128,0.35)` }}
                />
              </Stack>
              <Typography sx={{ mb: 2.5, fontSize: '0.9rem', color: C.muted }}>
                Click <strong style={{ color: C.text }}>Sign in with Cognito</strong> above, then use any
                account below. Every account shares the same password.
              </Typography>

              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1, alignItems: 'center', mb: 3 }}>
                <Typography sx={{ fontSize: '0.9rem', color: C.muted }}>Password for all accounts:</Typography>
                <Box
                  component="code"
                  sx={{
                    fontFamily: MONO,
                    fontWeight: 600,
                    color: C.teal,
                    px: 1.2,
                    py: 0.6,
                    borderRadius: '8px',
                    bgcolor: 'rgba(45,212,191,0.1)',
                    border: `1px solid ${C.border}`,
                  }}
                >
                  {DEMO_PASSWORD}
                </Box>
              </Stack>

              <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 2 }}>
                {DEMO_ACCOUNTS.map((account) => (
                  <Box key={account.email} sx={{ p: 2, border: `1px solid ${C.border}`, borderRadius: '12px', bgcolor: 'rgba(255,255,255,0.02)' }}>
                    <Chip
                      size="small"
                      label={account.role}
                      sx={{ mb: 1, bgcolor: 'rgba(129,140,248,0.14)', color: '#c7ccff', border: `1px solid ${C.border}` }}
                    />
                    <Typography sx={{ fontFamily: MONO, fontSize: '0.82rem', color: C.teal, wordBreak: 'break-all' }}>
                      {account.email}
                    </Typography>
                    <Typography sx={{ display: 'block', mt: 0.5, fontSize: '0.75rem', color: C.muted }}>
                      {account.note}
                    </Typography>
                  </Box>
                ))}
              </Box>

              <Divider sx={{ my: 3, '&::before, &::after': { borderColor: C.border } }} />
              <Stack spacing={1.25}>
                <Typography sx={{ fontSize: '0.85rem', color: C.muted, lineHeight: 1.55 }}>
                  🏥 <strong style={{ color: C.text }}>See multi-tenant isolation:</strong> every role also
                  exists for a second organization — swap <code>northcare</code> for <code>greenvalley</code>{' '}
                  (e.g.{' '}
                  <Box component="code" sx={{ fontFamily: MONO, color: C.teal }}>
                    provider@greenvalley.example.org
                  </Box>
                  ) and notice a NorthCare user can never see Green Valley’s data.
                </Typography>
                <Typography sx={{ fontSize: '0.85rem', color: C.muted, lineHeight: 1.55 }}>
                  🔄 <strong style={{ color: C.text }}>To switch roles:</strong> open a new Incognito window —
                  Cognito remembers your last sign-in, so a fresh window lets you log in as someone else.
                </Typography>
              </Stack>
            </Box>
          </Box>
        )}
      </Container>
    </Box>
  )
}

import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
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

  return (
    <Box
      sx={(theme) => ({
        minHeight: '100vh',
        bgcolor: 'background.default',
        backgroundImage: 'radial-gradient(900px 440px at 50% -8%, rgba(13,148,136,0.08), transparent 60%)',
        ...theme.applyStyles('dark', {
          backgroundImage: 'radial-gradient(900px 460px at 50% -8%, rgba(45,212,191,0.10), transparent 62%)',
        }),
      })}
    >
      <Container maxWidth="lg" sx={{ py: { xs: 5, md: 7 } }}>
        {/* Header: brand + domain eyebrow */}
        <Stack
          direction="row"
          sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1.5, mb: { xs: 5, md: 7 } }}
        >
          <Brand />
          <Typography
            sx={{
              fontSize: '0.75rem',
              fontWeight: 600,
              letterSpacing: '0.16em',
              textTransform: 'uppercase',
              color: 'text.secondary',
            }}
          >
            Care coordination &amp; claims
          </Typography>
        </Stack>

        {/* Centered headline + hook */}
        <Typography
          component="h1"
          sx={{
            fontFamily: '"Space Grotesk", sans-serif',
            fontWeight: 700,
            fontSize: { xs: '2rem', md: '2.9rem' },
            lineHeight: 1.12,
            letterSpacing: '-0.02em',
            textAlign: 'center',
            maxWidth: '18ch',
            mx: 'auto',
          }}
        >
          Care <Box component="span" sx={{ color: 'primary.main' }}>coordinated</Box>. Consent{' '}
          <Box component="span" sx={{ color: 'primary.main' }}>enforced</Box>. Decisions{' '}
          <Box component="span" sx={{ color: 'primary.main' }}>explained</Box>.
        </Typography>
        <Typography
          sx={{
            textAlign: 'center',
            color: 'text.secondary',
            fontSize: { xs: '1rem', md: '1.08rem' },
            lineHeight: 1.6,
            maxWidth: 680,
            mx: 'auto',
            mt: 2.5,
          }}
        >
          HealthCloud brings care coordination and synthetic claims processing into one platform, with access
          governed by patient relationships, consent, purpose, and field-level policies.
        </Typography>

        {/* Two balanced columns: security posture ↔ sign-in */}
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
            gap: { xs: 4, md: 6 },
            alignItems: 'start',
            mt: { xs: 5, md: 7 },
          }}
        >
          {/* Security posture — honest, backend-enforced capabilities */}
          <Box>
            <Typography
              sx={{
                fontSize: '0.75rem',
                fontWeight: 600,
                letterSpacing: '0.16em',
                textTransform: 'uppercase',
                color: 'text.secondary',
                mb: 1,
              }}
            >
              Security posture
            </Typography>
            {POSTURE.map((row, i) => (
              <Box
                key={row.k}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  py: 1.6,
                  borderBottom: i < POSTURE.length - 1 ? 1 : 0,
                  borderColor: 'divider',
                }}
              >
                <Box component="span" sx={{ fontFamily: MONO, fontSize: '0.9rem', color: 'text.primary' }}>
                  {row.k}
                </Box>
                <Box
                  component="span"
                  sx={{ display: 'flex', alignItems: 'center', gap: 1, fontFamily: MONO, fontSize: '0.85rem', color: 'success.main' }}
                >
                  <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'success.main' }} />
                  {row.v}
                </Box>
              </Box>
            ))}
            <Typography sx={{ mt: 2, fontSize: '0.82rem', color: 'text.secondary', lineHeight: 1.5 }}>
              Enforced by the backend on every request — provable in the audit trail.
            </Typography>
          </Box>

          {/* Sign-in card */}
          <Card sx={{ width: '100%' }}>
            <CardContent sx={{ p: { xs: 3, md: 3.5 } }}>
              <Typography component="h2" sx={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: '1.5rem' }}>
                Sign in
              </Typography>
              <Typography sx={{ mt: 0.5, mb: 3, fontSize: '0.95rem', color: 'text.secondary' }}>
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
                <Typography sx={{ display: 'block', mt: 1, fontSize: '0.75rem', color: 'text.secondary' }}>
                  Cognito sign-in isn’t configured in this environment
                  {import.meta.env.DEV ? ' — use the developer sign-in below.' : '.'}
                </Typography>
              )}

              {/* Developer sign-in — local dev builds only (hidden in the production bundle). */}
              {import.meta.env.DEV && (
                <>
                  <Divider sx={{ my: 3, fontSize: '0.75rem', color: 'text.secondary' }}>
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

                      <Button type="submit" variant="outlined" fullWidth disabled={login.isPending}>
                        {login.isPending ? 'Signing in…' : 'Developer sign-in'}
                      </Button>
                    </Stack>
                  </Box>
                </>
              )}
            </CardContent>
          </Card>
        </Box>

        {/* Demo credentials — shown whenever Cognito login is available (deployed app, or a local
            `local,cognito` run). Lets a reviewer/recruiter sign in and explore. Synthetic data only. */}
        {cognitoEnabled && (
          <Card sx={{ mt: { xs: 5, md: 7 }, maxWidth: 940, mx: 'auto' }}>
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
                {DEMO_ACCOUNTS.map((account) => (
                  <Box key={account.email} sx={{ p: 2, border: 1, borderColor: 'divider', borderRadius: '12px' }}>
                    <Chip size="small" label={account.role} sx={{ mb: 1 }} />
                    <Typography sx={{ fontFamily: MONO, fontSize: '0.82rem', color: 'primary.main', wordBreak: 'break-all' }}>
                      {account.email}
                    </Typography>
                    <Typography sx={{ display: 'block', mt: 0.5, fontSize: '0.75rem', color: 'text.secondary' }}>
                      {account.note}
                    </Typography>
                  </Box>
                ))}
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

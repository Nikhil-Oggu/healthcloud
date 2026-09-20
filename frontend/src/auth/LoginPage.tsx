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
import { ConstellationBackground } from '../components/ConstellationBackground'
import { constellation } from '../theme'

/**
 * The seeded demo users (local `dev-login` only — NO password/MFA). This developer sign-in is shown
 * ONLY in a dev build (`import.meta.env.DEV`); the production build shows just "Sign in with Cognito".
 */
const DEMO_USERS = [
  'patient@northcare.example.org',
  'provider@northcare.example.org',
  'coordinator@northcare.example.org',
  'reviewer@northcare.example.org',
  'admin@northcare.example.org',
  'patient@greenvalley.example.org',
  'provider@greenvalley.example.org',
  'coordinator@greenvalley.example.org',
  'reviewer@greenvalley.example.org',
  'admin@greenvalley.example.org',
]

// The BFF endpoint that begins the OIDC authorization-code flow. A full-page navigation (not fetch) —
// the response is a 302 to Cognito's hosted login on another origin.
const COGNITO_LOGIN_URL = '/oauth2/authorization/cognito'

const TRUST_POINTS = ['Tenant-isolated', 'Consent-aware', 'Tamper-evident audit']

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
  // `cognito` profile: no.) Drives whether the button is live or shown disabled with an explanation,
  // so a click never lands on the BFF's 500 when no OIDC client is wired.
  const authConfig = useQuery({
    queryKey: ['auth-config'],
    queryFn: () => api.authConfig(),
    staleTime: Infinity,
    retry: false,
  })
  // Treat "still loading" as available (optimistic) so the button doesn't flicker disabled; once the
  // probe resolves we know for certain. Only an explicit `false` disables it.
  const cognitoEnabled = authConfig.data?.cognitoEnabled ?? true

  // Already authenticated (e.g. navigated to /login directly) → go home.
  if (user) {
    return <Navigate to="/" replace />
  }

  return (
    <Box
      sx={{
        minHeight: '100vh',
        position: 'relative',
        overflow: 'hidden',
        display: 'flex',
        alignItems: 'center',
        color: constellation.dark.text,
        background: `radial-gradient(1100px 600px at 78% -8%, rgba(124,156,255,0.16), transparent 60%),
          radial-gradient(900px 520px at 8% 12%, rgba(94,234,212,0.13), transparent 55%),
          ${constellation.dark.bg}`,
      }}
    >
      <ConstellationBackground />

      <Container maxWidth="lg" sx={{ position: 'relative', zIndex: 1, py: { xs: 6, md: 8 } }}>
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '1.05fr 0.95fr' },
            gap: { xs: 5, md: 8 },
            alignItems: 'center',
          }}
        >
          {/* Hero */}
          <Box>
            <Brand onDark />
            <Typography
              component="h1"
              sx={{
                fontFamily: '"Space Grotesk", sans-serif',
                fontWeight: 700,
                fontSize: { xs: '2.25rem', md: '3.25rem' },
                lineHeight: 1.05,
                letterSpacing: '-0.02em',
                mt: 3,
              }}
            >
              Care that stays{' '}
              <Box
                component="span"
                sx={{
                  background: constellation.dark.gradient,
                  WebkitBackgroundClip: 'text',
                  backgroundClip: 'text',
                  color: 'transparent',
                }}
              >
                connected
              </Box>{' '}
              — and consent that stays in control.
            </Typography>
            <Typography
              sx={{
                mt: 2.5,
                fontSize: { xs: '1rem', md: '1.15rem' },
                lineHeight: 1.6,
                color: constellation.dark.muted,
                maxWidth: 520,
              }}
            >
              A consent-aware platform for coordinating patients, providers and claims across
              organizations — every access checked, every decision explainable, every record
              tamper-evident.
            </Typography>
            <Stack direction="row" spacing={1} sx={{ mt: 3.5, flexWrap: 'wrap', gap: 1 }}>
              {TRUST_POINTS.map((point) => (
                <Chip
                  key={point}
                  label={point}
                  variant="outlined"
                  sx={{
                    color: constellation.dark.text,
                    borderColor: 'rgba(94,234,212,0.35)',
                    bgcolor: 'rgba(94,234,212,0.06)',
                  }}
                />
              ))}
            </Stack>
          </Box>

          {/* Sign-in card */}
          <Card sx={{ width: '100%', maxWidth: 420, justifySelf: { md: 'end' }, boxShadow: '0 30px 60px -30px rgba(0,0,0,0.6)' }}>
            <CardContent sx={{ p: { xs: 3, md: 4 } }}>
              <Typography variant="h6" component="div" sx={{ fontWeight: 700 }}>
                Sign in
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 3, mt: 0.5 }}>
                Access your HealthCloud workspace.
              </Typography>

              {/* Primary, production login: redirect the whole page to the BFF's Cognito flow. Rendered
                  disabled (with an explanation) when this environment has no Cognito client configured, so
                  a click can never hit the BFF's 500. */}
              <Button
                variant="contained"
                fullWidth
                size="large"
                component={cognitoEnabled ? 'a' : 'button'}
                href={cognitoEnabled ? COGNITO_LOGIN_URL : undefined}
                disabled={!cognitoEnabled}
              >
                Sign in with Cognito
              </Button>
              {!cognitoEnabled && (
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                  Cognito sign-in isn’t configured in this environment
                  {import.meta.env.DEV ? ' — use the developer sign-in below.' : '.'}
                </Typography>
              )}

              {/* Developer sign-in — local dev builds only (hidden in the production bundle). */}
              {import.meta.env.DEV && (
                <>
                  <Divider sx={{ my: 3 }}>Developer sign-in (local only)</Divider>
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                    Choose a seeded demo user — no password (dev only).
                  </Typography>
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
                      >
                        {DEMO_USERS.map((u) => (
                          <MenuItem key={u} value={u}>
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

                      <Button type="submit" variant="outlined" disabled={login.isPending} fullWidth>
                        {login.isPending ? 'Signing in…' : 'Developer sign-in'}
                      </Button>
                    </Stack>
                  </Box>
                </>
              )}
            </CardContent>
          </Card>
        </Box>
      </Container>
    </Box>
  )
}

import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Navigate, useNavigate } from 'react-router-dom'
import { api, ApiClientError } from '../api/client'
import { ME_QUERY_KEY, useCurrentUser } from './useAuth'
import { PageHeading } from '../components/PageHeading'

/**
 * The seeded demo users (local `dev-login` only — NO password/MFA). This developer sign-in is shown
 * ONLY in a dev build (`import.meta.env.DEV`); the production build shows just "Sign in with Cognito".
 * Local part maps to a role: patient=PATIENT, provider=PROVIDER, coordinator=CARE_COORDINATOR,
 * reviewer=CLAIMS_REVIEWER, admin=ORG_ADMIN — across two tenants (NorthCare, Green Valley).
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

// The BFF endpoint that begins the OIDC authorization-code flow (Spring Security). A full-page
// navigation (not fetch) — the response is a 302 to Cognito's hosted login on another origin.
const COGNITO_LOGIN_URL = '/oauth2/authorization/cognito'

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

  // Already authenticated (e.g. navigated to /login directly) → go home.
  if (user) {
    return <Navigate to="/" replace />
  }

  return (
    <Box sx={{ display: 'flex', justifyContent: 'center', mt: 10, px: 2 }}>
      <Card sx={{ width: 420, maxWidth: '100%' }}>
        <CardContent>
          <PageHeading gutterBottom>
            HealthCloud
          </PageHeading>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
            Sign in with your HealthCloud account.
          </Typography>

          {/* Primary, production login: redirect the whole page to the BFF's Cognito flow. */}
          <Button
            variant="contained"
            fullWidth
            component="a"
            href={COGNITO_LOGIN_URL}
          >
            Sign in with Cognito
          </Button>

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
  )
}

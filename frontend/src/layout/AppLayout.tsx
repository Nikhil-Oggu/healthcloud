import {
  AppBar,
  Box,
  Button,
  Chip,
  Container,
  Stack,
  Toolbar,
  Typography,
} from '@mui/material'
import { Outlet, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'

/**
 * The authenticated shell: a top bar showing who you are (email · organization · roles), a
 * role-aware nav, and logout. Nav items are gated by role for convenience only — the backend still
 * authorizes every operation. Real destinations are added in Phase 2; they are placeholders now.
 */
export function AppLayout() {
  const { data: user } = useCurrentUser()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  async function handleLogout() {
    try {
      await api.logout()
    } finally {
      queryClient.clear()
      navigate('/login', { replace: true })
    }
  }

  const roles = user?.roles ?? []

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <AppBar position="static">
        <Toolbar>
          <Typography variant="h6" sx={{ fontWeight: 700, mr: 4 }}>
            HealthCloud
          </Typography>

          <Stack direction="row" spacing={1} sx={{ flexGrow: 1 }}>
            <Button color="inherit" onClick={() => navigate('/')}>
              Home
            </Button>
            {roles.some((r) => ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'].includes(r)) && (
              <Button color="inherit" onClick={() => navigate('/patients')}>
                Patients
              </Button>
            )}
            {roles.some((r) => ['PATIENT', 'PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'].includes(r)) && (
              <Button color="inherit" onClick={() => navigate('/requests')}>
                Requests
              </Button>
            )}
            {roles.includes('CARE_COORDINATOR') && (
              <Button color="inherit" disabled>
                Coordination
              </Button>
            )}
            {roles.some((r) => ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'].includes(r)) && (
              <Button color="inherit" onClick={() => navigate('/claims')}>
                Claims
              </Button>
            )}
            {roles.some((r) => ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'].includes(r)) && (
              <Button color="inherit" onClick={() => navigate('/coverage-plans')}>
                Coverage
              </Button>
            )}
            {roles.includes('ORG_ADMIN') && (
              <Button color="inherit" disabled>
                Administration
              </Button>
            )}
          </Stack>

          {user && (
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
              <Box sx={{ textAlign: 'right', lineHeight: 1.2 }}>
                <Typography variant="body2">{user.email}</Typography>
                <Typography variant="caption" sx={{ opacity: 0.85 }}>
                  {user.organizationName ?? 'No organization'}
                </Typography>
              </Box>
              <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                {roles.map((role) => (
                  <Chip key={role} label={role} size="small" color="secondary" />
                ))}
              </Box>
              <Button color="inherit" variant="outlined" onClick={handleLogout}>
                Log out
              </Button>
            </Stack>
          )}
        </Toolbar>
      </AppBar>

      <Container component="main" sx={{ py: 4, flexGrow: 1 }}>
        <Outlet />
      </Container>
    </Box>
  )
}

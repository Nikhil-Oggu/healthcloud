import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import {
  Button,
  Link,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { useAllBreakGlassGrants, useRevokeBreakGlass } from './useBreakGlass'
import { PageHeading } from '../components/PageHeading'

// Only an admin may revoke (mirrors the backend; an auditor sees the list but no button).
const REVOKE_ROLES = ['ORG_ADMIN']

export function AccessReviewPage() {
  const { data: user } = useCurrentUser()
  const grants = useAllBreakGlassGrants()
  const revoke = useRevokeBreakGlass()
  const [pendingId, setPendingId] = useState<string | null>(null)

  const canRevoke = (user?.roles ?? []).some((r) => REVOKE_ROLES.includes(r))

  if (grants.isPending) return <LoadingScreen />
  if (grants.isError) return <ErrorScreen error={grants.error} />

  function onRevoke(id: string) {
    revoke.mutate(id, { onSettled: () => setPendingId(null) })
  }

  return (
    <Stack spacing={3}>
      <PageHeading>Access review</PageHeading>
      <Typography variant="body2" color="text.secondary">
        Active break-glass emergency-access grants across the organization. Each was self-declared by a provider and
        is time-boxed and audited; an administrator can revoke one early to end access immediately.
      </Typography>

      <TableContainer component={Paper} elevation={0}>
        <Table aria-label="Active break-glass grants">
          <TableHead>
            <TableRow>
              <TableCell>Provider</TableCell>
              <TableCell>Patient</TableCell>
              <TableCell>Reason</TableCell>
              <TableCell>Granted</TableCell>
              <TableCell>Expires</TableCell>
              {canRevoke && <TableCell align="right">Action</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {grants.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={canRevoke ? 6 : 5}>
                  <Typography variant="body2" color="text.secondary">
                    No active emergency access.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              grants.data.map((g) => (
                <TableRow key={g.id} hover>
                  <TableCell>
                    <Tooltip title={g.providerUserId}>
                      <span>{g.providerName ?? g.providerUserId}</span>
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    <Link component={RouterLink} to={`/patients/${g.patientId}`}>
                      {g.patientId}
                    </Link>
                  </TableCell>
                  <TableCell>{g.reason}</TableCell>
                  <TableCell>
                    <Typography variant="caption" color="text.secondary">
                      {new Date(g.createdAt).toLocaleString()}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption" color="text.secondary">
                      {new Date(g.expiresAt).toLocaleString()}
                    </Typography>
                  </TableCell>
                  {canRevoke && (
                    <TableCell align="right">
                      {pendingId === g.id ? (
                        <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
                          <Button
                            size="small"
                            color="error"
                            variant="contained"
                            disabled={revoke.isPending}
                            onClick={() => onRevoke(g.id)}
                          >
                            {revoke.isPending ? 'Revoking…' : 'Confirm'}
                          </Button>
                          <Button size="small" onClick={() => setPendingId(null)} disabled={revoke.isPending}>
                            Cancel
                          </Button>
                        </Stack>
                      ) : (
                        <Button size="small" color="error" onClick={() => setPendingId(g.id)}>
                          Revoke
                        </Button>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}

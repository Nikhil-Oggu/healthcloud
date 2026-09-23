import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
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
import PersonOutlinedIcon from '@mui/icons-material/PersonOutlined'
import ScheduleOutlinedIcon from '@mui/icons-material/ScheduleOutlined'
import ShieldOutlinedIcon from '@mui/icons-material/ShieldOutlined'
import type { ReactNode } from 'react'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { useAllBreakGlassGrants, useRevokeBreakGlass } from './useBreakGlass'
import { PageHeading } from '../components/PageHeading'

// Only an admin may revoke (mirrors the backend; an auditor sees the list but no button).
const REVOKE_ROLES = ['ORG_ADMIN']

// The three at-a-glance facts about break-glass, shown as info cards above the grants table.
const INFO_CARDS: { icon: ReactNode; tint: string; color: string; title: string; body: string }[] = [
  {
    icon: <PersonOutlinedIcon />,
    tint: 'rgba(13,148,136,0.10)',
    color: 'primary.main',
    title: 'Provider-declared',
    body: 'Emergency access is self-declared by a provider.',
  },
  {
    icon: <ScheduleOutlinedIcon />,
    tint: 'rgba(79,70,229,0.10)',
    color: '#4f46e5',
    title: 'Time-boxed & audited',
    body: 'Each grant expires and is recorded.',
  },
  {
    icon: <ShieldOutlinedIcon />,
    tint: 'rgba(124,58,237,0.10)',
    color: '#7c3aed',
    title: 'Administrator control',
    body: 'Revoke a grant early to end access immediately.',
  },
]

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

  const rows = grants.data

  return (
    <Stack spacing={3}>
      {/* Breadcrumb */}
      <Box>
        <Typography variant="body2" color="text.secondary">
          {user?.organizationName ?? '—'}
          <Box component="span" sx={{ mx: 1, opacity: 0.6 }}>
            /
          </Box>
          Governance
        </Typography>
        <Divider sx={{ mt: 1.5 }} />
      </Box>

      {/* Title + subtitle */}
      <Box>
        <PageHeading sx={{ mb: 0.5 }}>Access review</PageHeading>
        <Typography color="text.secondary">Review emergency-access grants across your organization.</Typography>
      </Box>

      {/* Info cards */}
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' }, gap: 3 }}>
        {INFO_CARDS.map((c) => (
          <Card key={c.title}>
            <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
              <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start' }}>
                <Box
                  sx={{
                    flexShrink: 0,
                    width: 48,
                    height: 48,
                    borderRadius: '50%',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    bgcolor: c.tint,
                    color: c.color,
                  }}
                >
                  {c.icon}
                </Box>
                <Box>
                  <Typography sx={{ fontWeight: 700 }}>{c.title}</Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                    {c.body}
                  </Typography>
                </Box>
              </Stack>
            </CardContent>
          </Card>
        ))}
      </Box>

      {/* Active grants section */}
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
          Active emergency access
        </Typography>
        <Chip
          label={rows.length}
          size="small"
          sx={{ bgcolor: 'rgba(13,148,136,0.10)', color: 'primary.dark', fontWeight: 600 }}
        />
      </Stack>

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
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={canRevoke ? 6 : 5}>
                  <Box
                    sx={{
                      display: 'flex',
                      flexDirection: 'column',
                      alignItems: 'center',
                      gap: 1,
                      py: 6,
                      textAlign: 'center',
                    }}
                  >
                    <Box
                      sx={{
                        width: 64,
                        height: 64,
                        borderRadius: '50%',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        bgcolor: 'action.hover',
                        color: 'text.secondary',
                        mb: 0.5,
                      }}
                    >
                      <ShieldOutlinedIcon />
                    </Box>
                    <Typography sx={{ fontWeight: 700 }}>No active emergency access.</Typography>
                    <Typography variant="body2" color="text.secondary">
                      Active grants will appear here for review.
                    </Typography>
                  </Box>
                </TableCell>
              </TableRow>
            ) : (
              rows.map((g) => (
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

import { Link as RouterLink } from 'react-router-dom'
import {
  Chip,
  Link,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { usePatients } from '../patients/usePatients'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { referralStatusColor } from './statusColor'
import { CreateReferralForm } from './CreateReferralForm'
import { useReferrals } from './useReferral'

// Roles allowed to request a referral (mirrors the backend gate; the server still enforces it).
const REQUEST_ROLES = ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN']

export function ReferralsPage() {
  const { data: user } = useCurrentUser()
  const referrals = useReferrals()
  const patients = usePatients()
  const canRequest = (user?.roles ?? []).some((r) => REQUEST_ROLES.includes(r))

  if (referrals.isPending) return <LoadingScreen />
  if (referrals.isError) return <ErrorScreen error={referrals.error} />

  const nameFor = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'

  return (
    <Stack spacing={3}>
      <Typography variant="h5">Referrals</Typography>

      {canRequest && <CreateReferralForm />}

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Referrals">
          <TableHead>
            <TableRow>
              <TableCell>Ref #</TableCell>
              <TableCell>Patient</TableCell>
              <TableCell>Specialty</TableCell>
              <TableCell>Reason</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {referrals.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5}>
                  <Typography variant="body2" color="text.secondary">
                    No referrals yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              referrals.data.map((r) => (
                <TableRow key={r.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/referrals/${r.id}`}>
                      {r.referralNumber}
                    </Link>
                  </TableCell>
                  <TableCell>{nameFor(r.patientId)}</TableCell>
                  <TableCell>{r.specialty}</TableCell>
                  <TableCell>
                    {r.reasonCode}{' '}
                    <Typography component="span" variant="caption" color="text.secondary">
                      ({r.reasonCodeSystem})
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip label={r.status} size="small" color={referralStatusColor(r.status)} />
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}

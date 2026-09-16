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
import { useClaims } from '../claims/useClaims'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { appealStatusColor } from './statusColor'
import { CreateAppealForm } from './CreateAppealForm'
import { useAppeals } from './useAppeal'

// Roles allowed to submit an appeal (mirrors the backend gate; the server still enforces it).
const SUBMIT_ROLES = ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN']

export function AppealsPage() {
  const { data: user } = useCurrentUser()
  const appeals = useAppeals()
  const patients = usePatients()
  const claims = useClaims()
  const canSubmit = (user?.roles ?? []).some((r) => SUBMIT_ROLES.includes(r))

  if (appeals.isPending) return <LoadingScreen />
  if (appeals.isError) return <ErrorScreen error={appeals.error} />

  const patientName = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'
  const claimNumber = (claimId: string) =>
    (claims.data ?? []).find((c) => c.id === claimId)?.claimNumber ?? '—'

  return (
    <Stack spacing={3}>
      <Typography variant="h5">Appeals</Typography>

      {canSubmit && <CreateAppealForm />}

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Appeals">
          <TableHead>
            <TableRow>
              <TableCell>Appeal #</TableCell>
              <TableCell>Patient</TableCell>
              <TableCell>Claim</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {appeals.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4}>
                  <Typography variant="body2" color="text.secondary">
                    No appeals yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              appeals.data.map((a) => (
                <TableRow key={a.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/appeals/${a.id}`}>
                      {a.appealNumber}
                    </Link>
                  </TableCell>
                  <TableCell>{patientName(a.patientId)}</TableCell>
                  <TableCell>{claimNumber(a.claimId)}</TableCell>
                  <TableCell>
                    <Chip label={a.status} size="small" color={appealStatusColor(a.status)} />
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

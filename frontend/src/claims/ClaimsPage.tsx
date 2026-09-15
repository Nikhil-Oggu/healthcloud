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
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { claimStatusColor } from './statusColor'
import { useClaims } from './useClaims'

/** Format a numeric amount as USD currency for display. */
export function money(amount: number): string {
  return amount.toLocaleString(undefined, { style: 'currency', currency: 'USD' })
}

export function ClaimsPage() {
  const claims = useClaims()
  const patients = usePatients()

  if (claims.isPending) return <LoadingScreen />
  if (claims.isError) return <ErrorScreen error={claims.error} />

  const nameFor = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'

  return (
    <Stack spacing={3}>
      <Typography variant="h5">Claims</Typography>

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Claims">
          <TableHead>
            <TableRow>
              <TableCell>Claim #</TableCell>
              <TableCell>Patient</TableCell>
              <TableCell>Service date</TableCell>
              <TableCell align="right">Total charge</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {claims.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5}>
                  <Typography variant="body2" color="text.secondary">
                    No claims yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              claims.data.map((c) => (
                <TableRow key={c.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/claims/${c.id}`}>
                      {c.claimNumber}
                    </Link>
                  </TableCell>
                  <TableCell>{nameFor(c.patientId)}</TableCell>
                  <TableCell>{c.serviceDate}</TableCell>
                  <TableCell align="right">{money(c.totalChargeAmount)}</TableCell>
                  <TableCell>
                    <Chip label={c.status} size="small" color={claimStatusColor(c.status)} />
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

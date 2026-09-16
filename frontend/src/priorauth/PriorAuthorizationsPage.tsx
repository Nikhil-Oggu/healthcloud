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
import { priorAuthStatusColor } from './statusColor'
import { usePriorAuthorizations } from './usePriorAuth'

export function PriorAuthorizationsPage() {
  const auths = usePriorAuthorizations()
  const patients = usePatients()

  if (auths.isPending) return <LoadingScreen />
  if (auths.isError) return <ErrorScreen error={auths.error} />

  const nameFor = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'

  return (
    <Stack spacing={3}>
      <Typography variant="h5">Prior authorizations</Typography>

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Prior authorizations">
          <TableHead>
            <TableRow>
              <TableCell>Auth #</TableCell>
              <TableCell>Patient</TableCell>
              <TableCell>Procedure</TableCell>
              <TableCell>Requested from</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {auths.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5}>
                  <Typography variant="body2" color="text.secondary">
                    No prior authorizations yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              auths.data.map((a) => (
                <TableRow key={a.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/prior-authorizations/${a.id}`}>
                      {a.authNumber}
                    </Link>
                  </TableCell>
                  <TableCell>{nameFor(a.patientId)}</TableCell>
                  <TableCell>
                    {a.procedureCode}{' '}
                    <Typography component="span" variant="caption" color="text.secondary">
                      ({a.procedureCodeSystem})
                    </Typography>
                  </TableCell>
                  <TableCell>{a.requestedServiceFrom}</TableCell>
                  <TableCell>
                    <Chip label={a.status} size="small" color={priorAuthStatusColor(a.status)} />
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

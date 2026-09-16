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
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { useMyBreakGlassGrants } from './useBreakGlass'

/**
 * A provider's break-glass home: the patients they currently hold time-boxed emergency access to. Each links to the
 * (now reachable) patient. Read-only — access is invoked from a patient's denied page (see {@link BreakGlassPanel}).
 */
export function MyBreakGlassPage() {
  const grants = useMyBreakGlassGrants()

  if (grants.isPending) return <LoadingScreen />
  if (grants.isError) return <ErrorScreen error={grants.error} />

  return (
    <Stack spacing={3}>
      <Typography variant="h5">Emergency access</Typography>
      <Typography variant="body2" color="text.secondary">
        Patients you currently have break-glass access to. Access is time-boxed and every use is audited. To break
        the glass for a new patient, open that patient and use the emergency-access panel.
      </Typography>

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Break-glass grants">
          <TableHead>
            <TableRow>
              <TableCell>Patient</TableCell>
              <TableCell>Reason</TableCell>
              <TableCell>Granted</TableCell>
              <TableCell>Expires</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {grants.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5}>
                  <Typography variant="body2" color="text.secondary">
                    No active emergency access.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              grants.data.map((g) => (
                <TableRow key={g.id} hover>
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
                  <TableCell>
                    <Chip
                      label={g.active ? 'Active' : 'Expired'}
                      size="small"
                      color={g.active ? 'success' : 'default'}
                    />
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

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
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { reprocessingStatusColor } from './statusColor'
import { CreateReprocessingBatchForm } from './CreateReprocessingBatchForm'
import { useReprocessingBatches } from './useReprocessing'

// Roles allowed to run a batch (mirrors the backend gate; the server still enforces it).
const RUN_ROLES = ['CLAIMS_REVIEWER', 'ORG_ADMIN']

export function ReprocessingBatchesPage() {
  const { data: user } = useCurrentUser()
  const batches = useReprocessingBatches()
  const canRun = (user?.roles ?? []).some((r) => RUN_ROLES.includes(r))

  if (batches.isPending) return <LoadingScreen />
  if (batches.isError) return <ErrorScreen error={batches.error} />

  return (
    <Stack spacing={3}>
      <Typography variant="h5">Reprocessing</Typography>

      {canRun && <CreateReprocessingBatchForm />}

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Reprocessing batches">
          <TableHead>
            <TableRow>
              <TableCell>Batch #</TableCell>
              <TableCell>Plan</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right">Succeeded</TableCell>
              <TableCell align="right">Failed</TableCell>
              <TableCell align="right">Total</TableCell>
              <TableCell>Run</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {batches.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7}>
                  <Typography variant="body2" color="text.secondary">
                    No batches yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              batches.data.map((b) => (
                <TableRow key={b.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/reprocessing/${b.id}`}>
                      {b.batchNumber}
                    </Link>
                  </TableCell>
                  <TableCell>{b.coveragePlanName ?? '—'}</TableCell>
                  <TableCell>
                    <Chip label={b.status} size="small" color={reprocessingStatusColor(b.status)} />
                  </TableCell>
                  <TableCell align="right">{b.succeededCount}</TableCell>
                  <TableCell align="right">{b.failedCount}</TableCell>
                  <TableCell align="right">{b.totalCount}</TableCell>
                  <TableCell>
                    <Typography variant="caption" color="text.secondary">
                      {new Date(b.createdAt).toLocaleString()}
                    </Typography>
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

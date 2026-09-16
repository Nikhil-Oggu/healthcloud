import { Link as RouterLink, useParams } from 'react-router-dom'
import {
  Box,
  Card,
  CardContent,
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
import { useClaims } from '../claims/useClaims'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { itemOutcomeColor, reprocessingStatusColor } from './statusColor'
import { useReprocessingBatch } from './useReprocessing'

export function ReprocessingBatchDetailPage() {
  const { id = '' } = useParams()
  const batch = useReprocessingBatch(id)
  const claims = useClaims()

  if (batch.isPending) return <LoadingScreen />
  if (batch.isError) return <ErrorScreen error={batch.error} />

  const b = batch.data
  const claimNumber = (claimId: string) =>
    (claims.data ?? []).find((c) => c.id === claimId)?.claimNumber ?? claimId

  return (
    <Stack spacing={3}>
      <Box>
        <Link component={RouterLink} to="/reprocessing">
          ← Back to reprocessing
        </Link>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1 }}>
            <Typography variant="h5">Batch {b.batchNumber}</Typography>
            <Chip label={b.status} color={reprocessingStatusColor(b.status)} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Plan: {b.coveragePlanName ?? '—'}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            {b.succeededCount} succeeded · {b.failedCount} failed · {b.totalCount} total
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
            Started {new Date(b.createdAt).toLocaleString()}
            {b.finishedAt ? ` · finished ${new Date(b.finishedAt).toLocaleString()}` : ''}
          </Typography>
        </CardContent>
      </Card>

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Reprocessed claims">
          <TableHead>
            <TableRow>
              <TableCell>Claim</TableCell>
              <TableCell>Outcome</TableCell>
              <TableCell align="right">New version</TableCell>
              <TableCell>Detail</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {b.items.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4}>
                  <Typography variant="body2" color="text.secondary">
                    No claims were in scope for this plan.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              b.items.map((item) => (
                <TableRow key={item.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/claims/${item.claimId}`}>
                      {claimNumber(item.claimId)}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Chip label={item.outcome} size="small" color={itemOutcomeColor(item.outcome)} />
                  </TableCell>
                  <TableCell align="right">{item.adjudicationVersion ?? '—'}</TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">
                      {item.message ?? ''}
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

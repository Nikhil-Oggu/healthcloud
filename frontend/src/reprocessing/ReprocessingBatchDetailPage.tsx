import { Link as RouterLink, useParams } from 'react-router-dom'
import { BackLink } from '../components/BackLink'
import {
  Box,
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
  Typography,
} from '@mui/material'
import { useClaims } from '../claims/useClaims'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { itemOutcomeColor, reprocessingStatusColor } from './statusColor'
import { useReprocessingBatch } from './useReprocessing'
import { PageHeading } from '../components/PageHeading'

export function ReprocessingBatchDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
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
        <BackLink to="/reprocessing" label="Back to reprocessing" />
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          {user?.organizationName ?? '—'}
          <Box component="span" sx={{ mx: 1, opacity: 0.6 }}>
            /
          </Box>
          Claims &amp; coverage
        </Typography>
        <Divider sx={{ mt: 1.5 }} />
      </Box>

      <Box>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}>
          <PageHeading sx={{ mb: 0 }}>Batch {b.batchNumber}</PageHeading>
          <Chip label={b.status} color={reprocessingStatusColor(b.status)} size="small" />
        </Stack>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          Plan: {b.coveragePlanName ?? '—'}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          {b.succeededCount} succeeded · {b.failedCount} failed · {b.totalCount} total
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
          Started {new Date(b.createdAt).toLocaleString()}
          {b.finishedAt ? ` · finished ${new Date(b.finishedAt).toLocaleString()}` : ''}
        </Typography>
      </Box>

      <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
        Reprocessed claims
      </Typography>

      <TableContainer component={Paper} elevation={0}>
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

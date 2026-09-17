import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import {
  Chip,
  Link,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
  TextField,
  Typography,
} from '@mui/material'
import type { ReprocessingBatchStatus } from '../api/types'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { reprocessingStatusColor } from './statusColor'
import { CreateReprocessingBatchForm } from './CreateReprocessingBatchForm'
import { useReprocessingBatches } from './useReprocessing'

// Roles allowed to run a batch (mirrors the backend gate; the server still enforces it).
const RUN_ROLES = ['CLAIMS_REVIEWER', 'ORG_ADMIN']

// The statuses a batch can hold, for the filter dropdown (mirrors the ReprocessingBatchStatus union).
const STATUSES: ReprocessingBatchStatus[] = ['RUNNING', 'COMPLETED', 'COMPLETED_WITH_ERRORS']

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

/** Columns the backend allows sorting by (mirrors the controller allowlist); Plan/counts are not sortable. */
type SortField = 'batchNumber' | 'status'
type SortDir = 'asc' | 'desc'

export function ReprocessingBatchesPage() {
  const { data: user } = useCurrentUser()
  const canRun = (user?.roles ?? []).some((r) => RUN_ROLES.includes(r))

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [status, setStatus] = useState<ReprocessingBatchStatus | ''>('')
  // No active sort by default → the backend applies its default (createdAt DESC = newest first).
  const [sortField, setSortField] = useState<SortField | null>(null)
  const [sortDir, setSortDir] = useState<SortDir>('asc')

  const sort = sortField ? `${sortField},${sortDir}` : undefined
  const batches = useReprocessingBatches({ page, size, sort, status: status || undefined })

  // A column header toggles asc → desc on repeat click; a new column starts ascending. Any change resets to page 0.
  function toggleSort(field: SortField) {
    if (sortField === field) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortField(field)
      setSortDir('asc')
    }
    setPage(0)
  }

  if (batches.isPending) return <LoadingScreen />
  if (batches.isError) return <ErrorScreen error={batches.error} />

  const rows = batches.data.content

  const sortableHeader = (field: SortField, label: string) => (
    <TableCell sortDirection={sortField === field ? sortDir : false}>
      <TableSortLabel
        active={sortField === field}
        direction={sortField === field ? sortDir : 'asc'}
        onClick={() => toggleSort(field)}
      >
        {label}
      </TableSortLabel>
    </TableCell>
  )

  return (
    <Stack spacing={3}>
      <Typography variant="h5">Reprocessing</Typography>

      {canRun && <CreateReprocessingBatchForm />}

      <TextField
        select
        label="Status"
        size="small"
        value={status}
        onChange={(e) => {
          setStatus(e.target.value as ReprocessingBatchStatus | '')
          setPage(0)
        }}
        sx={{ maxWidth: 260 }}
      >
        <MenuItem value="">All statuses</MenuItem>
        {STATUSES.map((s) => (
          <MenuItem key={s} value={s}>
            {s}
          </MenuItem>
        ))}
      </TextField>

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Reprocessing batches">
          <TableHead>
            <TableRow>
              {sortableHeader('batchNumber', 'Batch #')}
              <TableCell>Plan</TableCell>
              {sortableHeader('status', 'Status')}
              <TableCell align="right">Succeeded</TableCell>
              <TableCell align="right">Failed</TableCell>
              <TableCell align="right">Total</TableCell>
              <TableCell>Run</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7}>
                  <Typography variant="body2" color="text.secondary">
                    No batches yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              rows.map((b) => (
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
        <TablePagination
          component="div"
          count={batches.data.totalElements}
          page={batches.data.page}
          onPageChange={(_, newPage) => setPage(newPage)}
          rowsPerPage={size}
          onRowsPerPageChange={(e) => {
            setSize(parseInt(e.target.value, 10))
            setPage(0)
          }}
          rowsPerPageOptions={ROWS_PER_PAGE_OPTIONS}
        />
      </TableContainer>
    </Stack>
  )
}

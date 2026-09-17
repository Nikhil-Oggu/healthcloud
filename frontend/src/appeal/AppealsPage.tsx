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
import type { AppealStatus } from '../api/types'
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

// The statuses an appeal can hold, for the filter dropdown (mirrors the AppealStatus union).
const STATUSES: AppealStatus[] = ['SUBMITTED', 'UPHELD', 'OVERTURNED', 'WITHDRAWN']

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

/** Columns the backend allows sorting by; Patient and Claim are resolved client-side (not sortable). */
type SortField = 'appealNumber' | 'status'
type SortDir = 'asc' | 'desc'

export function AppealsPage() {
  const { data: user } = useCurrentUser()
  const canSubmit = (user?.roles ?? []).some((r) => SUBMIT_ROLES.includes(r))

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [status, setStatus] = useState<AppealStatus | ''>('')
  // No active sort by default → the backend applies its default (createdAt DESC = newest first).
  const [sortField, setSortField] = useState<SortField | null>(null)
  const [sortDir, setSortDir] = useState<SortDir>('asc')

  const sort = sortField ? `${sortField},${sortDir}` : undefined
  const appeals = useAppeals({ page, size, sort, status: status || undefined })
  const patients = usePatients()
  const claims = useClaims()

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

  if (appeals.isPending) return <LoadingScreen />
  if (appeals.isError) return <ErrorScreen error={appeals.error} />

  const patientName = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'
  const claimNumber = (claimId: string) =>
    (claims.data ?? []).find((c) => c.id === claimId)?.claimNumber ?? '—'

  const rows = appeals.data.content

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
      <Typography variant="h5">Appeals</Typography>

      {canSubmit && <CreateAppealForm />}

      <TextField
        select
        label="Status"
        size="small"
        value={status}
        onChange={(e) => {
          setStatus(e.target.value as AppealStatus | '')
          setPage(0)
        }}
        sx={{ maxWidth: 220 }}
      >
        <MenuItem value="">All statuses</MenuItem>
        {STATUSES.map((s) => (
          <MenuItem key={s} value={s}>
            {s}
          </MenuItem>
        ))}
      </TextField>

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Appeals">
          <TableHead>
            <TableRow>
              {sortableHeader('appealNumber', 'Appeal #')}
              <TableCell>Patient</TableCell>
              <TableCell>Claim</TableCell>
              {sortableHeader('status', 'Status')}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4}>
                  <Typography variant="body2" color="text.secondary">
                    No appeals yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              rows.map((a) => (
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
        <TablePagination
          component="div"
          count={appeals.data.totalElements}
          page={appeals.data.page}
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

import { useEffect, useState } from 'react'
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
import { PageHeading } from '../components/PageHeading'

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
  // The raw search box, and the debounced value we actually query with (so we don't fire per keystroke).
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  // No active sort by default → the backend applies its default (createdAt DESC = newest first).
  const [sortField, setSortField] = useState<SortField | null>(null)
  const [sortDir, setSortDir] = useState<SortDir>('asc')

  // Debounce the search box: settle for 300ms after the last keystroke before querying the server.
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300)
    return () => clearTimeout(timer)
  }, [search])

  const sort = sortField ? `${sortField},${sortDir}` : undefined
  const appeals = useAppeals({
    page,
    size,
    sort,
    status: status || undefined,
    q: debouncedSearch.trim() || undefined,
  })
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
      <PageHeading>Appeals</PageHeading>

      {canSubmit && <CreateAppealForm />}

      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', rowGap: 2 }}>
        <TextField
          label="Search appeal #"
          size="small"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }}
          placeholder="e.g. APL-1A2B"
          sx={{ maxWidth: 260 }}
        />

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
      </Stack>

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

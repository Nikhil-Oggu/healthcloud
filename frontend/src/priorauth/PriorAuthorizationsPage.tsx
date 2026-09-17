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
import type { PriorAuthorizationStatus } from '../api/types'
import { usePatients } from '../patients/usePatients'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { priorAuthStatusColor } from './statusColor'
import { CreatePriorAuthForm } from './CreatePriorAuthForm'
import { usePriorAuthorizations } from './usePriorAuth'

// Roles allowed to request a prior auth (mirrors the backend gate; the server still enforces it).
const REQUEST_ROLES = ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN']

// The statuses a prior auth can hold, for the filter dropdown (mirrors the PriorAuthorizationStatus union).
const STATUSES: PriorAuthorizationStatus[] = ['REQUESTED', 'APPROVED', 'DENIED', 'CANCELLED']

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

/** Columns the backend allows sorting by (mirrors the controller allowlist); Patient is resolved client-side. */
type SortField = 'authNumber' | 'procedureCode' | 'requestedServiceFrom' | 'status'
type SortDir = 'asc' | 'desc'

export function PriorAuthorizationsPage() {
  const { data: user } = useCurrentUser()
  const canRequest = (user?.roles ?? []).some((r) => REQUEST_ROLES.includes(r))

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [status, setStatus] = useState<PriorAuthorizationStatus | ''>('')
  // No active sort by default → the backend applies its default (createdAt DESC = newest first).
  const [sortField, setSortField] = useState<SortField | null>(null)
  const [sortDir, setSortDir] = useState<SortDir>('asc')

  const sort = sortField ? `${sortField},${sortDir}` : undefined
  const auths = usePriorAuthorizations({ page, size, sort, status: status || undefined })
  const patients = usePatients()

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

  if (auths.isPending) return <LoadingScreen />
  if (auths.isError) return <ErrorScreen error={auths.error} />

  const nameFor = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'

  const rows = auths.data.content

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
      <Typography variant="h5">Prior authorizations</Typography>

      {canRequest && <CreatePriorAuthForm />}

      <TextField
        select
        label="Status"
        size="small"
        value={status}
        onChange={(e) => {
          setStatus(e.target.value as PriorAuthorizationStatus | '')
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
        <Table aria-label="Prior authorizations">
          <TableHead>
            <TableRow>
              {sortableHeader('authNumber', 'Auth #')}
              <TableCell>Patient</TableCell>
              {sortableHeader('procedureCode', 'Procedure')}
              {sortableHeader('requestedServiceFrom', 'Requested from')}
              {sortableHeader('status', 'Status')}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5}>
                  <Typography variant="body2" color="text.secondary">
                    No prior authorizations yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              rows.map((a) => (
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
        <TablePagination
          component="div"
          count={auths.data.totalElements}
          page={auths.data.page}
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

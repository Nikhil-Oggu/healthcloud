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
import type { ReferralStatus } from '../api/types'
import { usePatients } from '../patients/usePatients'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { referralStatusColor } from './statusColor'
import { CreateReferralForm } from './CreateReferralForm'
import { useReferrals } from './useReferral'

// Roles allowed to request a referral (mirrors the backend gate; the server still enforces it).
const REQUEST_ROLES = ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN']

// The statuses a referral can hold, for the filter dropdown (mirrors the ReferralStatus union).
const STATUSES: ReferralStatus[] = ['REQUESTED', 'APPROVED', 'DENIED', 'CANCELLED']

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

/** Columns the backend allows sorting by (mirrors the controller allowlist); Patient is resolved client-side. */
type SortField = 'referralNumber' | 'specialty' | 'reasonCode' | 'status'
type SortDir = 'asc' | 'desc'

export function ReferralsPage() {
  const { data: user } = useCurrentUser()
  const canRequest = (user?.roles ?? []).some((r) => REQUEST_ROLES.includes(r))

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [status, setStatus] = useState<ReferralStatus | ''>('')
  // No active sort by default → the backend applies its default (createdAt DESC = newest first).
  const [sortField, setSortField] = useState<SortField | null>(null)
  const [sortDir, setSortDir] = useState<SortDir>('asc')

  const sort = sortField ? `${sortField},${sortDir}` : undefined
  const referrals = useReferrals({ page, size, sort, status: status || undefined })
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

  if (referrals.isPending) return <LoadingScreen />
  if (referrals.isError) return <ErrorScreen error={referrals.error} />

  const nameFor = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'

  const rows = referrals.data.content

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
      <Typography variant="h5">Referrals</Typography>

      {canRequest && <CreateReferralForm />}

      <TextField
        select
        label="Status"
        size="small"
        value={status}
        onChange={(e) => {
          setStatus(e.target.value as ReferralStatus | '')
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
        <Table aria-label="Referrals">
          <TableHead>
            <TableRow>
              {sortableHeader('referralNumber', 'Ref #')}
              <TableCell>Patient</TableCell>
              {sortableHeader('specialty', 'Specialty')}
              {sortableHeader('reasonCode', 'Reason')}
              {sortableHeader('status', 'Status')}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5}>
                  <Typography variant="body2" color="text.secondary">
                    No referrals yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              rows.map((r) => (
                <TableRow key={r.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/referrals/${r.id}`}>
                      {r.referralNumber}
                    </Link>
                  </TableCell>
                  <TableCell>{nameFor(r.patientId)}</TableCell>
                  <TableCell>{r.specialty}</TableCell>
                  <TableCell>
                    {r.reasonCode}{' '}
                    <Typography component="span" variant="caption" color="text.secondary">
                      ({r.reasonCodeSystem})
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip label={r.status} size="small" color={referralStatusColor(r.status)} />
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        <TablePagination
          component="div"
          count={referrals.data.totalElements}
          page={referrals.data.page}
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

import { useEffect, useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import {
  Box,
  Card,
  CardContent,
  Chip,
  Divider,
  InputAdornment,
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
import SearchOutlinedIcon from '@mui/icons-material/SearchOutlined'
import type { ClaimReviewStatus } from '../api/types'
import { usePatients } from '../patients/usePatients'
import { useClaims } from '../claims/useClaims'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { EmptyState } from '../components/EmptyState'
import { claimReviewStatusColor } from './statusColor'
import { CreateClaimReviewForm } from './CreateClaimReviewForm'
import { useClaimReviews } from './useClaimReview'
import { PageHeading } from '../components/PageHeading'

// Roles allowed to open a review (mirrors the backend gate; the server still enforces it).
const OPEN_ROLES = ['CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN']

// The statuses a review can hold, for the filter dropdown (mirrors the ClaimReviewStatus union).
const STATUSES: ClaimReviewStatus[] = ['OPEN', 'RESOLVED', 'CANCELLED']

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

/** Columns the backend allows sorting by; Patient and Claim are resolved client-side (not sortable). */
type SortField = 'reviewNumber' | 'status'
type SortDir = 'asc' | 'desc'

export function ClaimReviewsPage() {
  const { data: user } = useCurrentUser()
  const canOpen = (user?.roles ?? []).some((r) => OPEN_ROLES.includes(r))

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [status, setStatus] = useState<ClaimReviewStatus | ''>('')
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
  const reviews = useClaimReviews({
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

  if (reviews.isPending) return <LoadingScreen />
  if (reviews.isError) return <ErrorScreen error={reviews.error} />

  const patientName = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'
  const claimNumber = (claimId: string) =>
    (claims.data ?? []).find((c) => c.id === claimId)?.claimNumber ?? '—'

  const rows = reviews.data.content

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

  const historyCard = (
    <Card>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        {/* Filters: search + status */}
        <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', rowGap: 2, mb: 2, alignItems: 'center' }}>
          <TextField
            size="small"
            placeholder="Search review #"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              setPage(0)
            }}
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchOutlinedIcon fontSize="small" />
                  </InputAdornment>
                ),
              },
              htmlInput: { 'aria-label': 'Search review #' },
            }}
            sx={{ flexGrow: 1, minWidth: 200, maxWidth: 360 }}
          />

          <TextField
            select
            label="Status"
            size="small"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as ClaimReviewStatus | '')
              setPage(0)
            }}
            sx={{ minWidth: 180 }}
          >
            <MenuItem value="">All statuses</MenuItem>
            {STATUSES.map((s) => (
              <MenuItem key={s} value={s}>
                {s}
              </MenuItem>
            ))}
          </TextField>
        </Stack>

        <TableContainer component={Paper} elevation={0}>
          <Table aria-label="Claim reviews">
            <TableHead>
              <TableRow>
                {sortableHeader('reviewNumber', 'Review #')}
                <TableCell>Patient</TableCell>
                <TableCell>Claim</TableCell>
                {sortableHeader('status', 'Status')}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4}>
                    <EmptyState message="No reviews yet." />
                  </TableCell>
                </TableRow>
              ) : (
                rows.map((r) => (
                  <TableRow key={r.id} hover>
                    <TableCell>
                      <Link component={RouterLink} to={`/claim-reviews/${r.id}`} sx={{ fontWeight: 600 }}>
                        {r.reviewNumber}
                      </Link>
                    </TableCell>
                    <TableCell>{patientName(r.patientId)}</TableCell>
                    <TableCell>{claimNumber(r.claimId)}</TableCell>
                    <TableCell>
                      <Chip label={r.status} size="small" color={claimReviewStatusColor(r.status)} />
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
          <TablePagination
            component="div"
            count={reviews.data.totalElements}
            page={reviews.data.page}
            onPageChange={(_, newPage) => setPage(newPage)}
            rowsPerPage={size}
            onRowsPerPageChange={(e) => {
              setSize(parseInt(e.target.value, 10))
              setPage(0)
            }}
            rowsPerPageOptions={ROWS_PER_PAGE_OPTIONS}
          />
        </TableContainer>
      </CardContent>
    </Card>
  )

  return (
    <Stack spacing={3}>
      {/* Breadcrumb */}
      <Box>
        <Typography variant="body2" color="text.secondary">
          {user?.organizationName ?? '—'}
          <Box component="span" sx={{ mx: 1, opacity: 0.6 }}>
            /
          </Box>
          Claims &amp; coverage
        </Typography>
        <Divider sx={{ mt: 1.5 }} />
      </Box>

      {/* Title + subtitle */}
      <Box>
        <PageHeading sx={{ mb: 0.5 }}>Manual review</PageHeading>
        <Typography color="text.secondary">Flag a claim and track its review.</Typography>
      </Box>

      {canOpen ? (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '3fr 2fr' }, gap: 3, alignItems: 'start' }}>
          {historyCard}
          <CreateClaimReviewForm />
        </Box>
      ) : (
        historyCard
      )}
    </Stack>
  )
}

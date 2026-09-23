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
  Stack,
  TablePagination,
  TextField,
  Typography,
} from '@mui/material'
import SearchOutlinedIcon from '@mui/icons-material/SearchOutlined'
import type { ClaimStatus } from '../api/types'
import { usePatients } from '../patients/usePatients'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { EmptyState } from '../components/EmptyState'
import { claimStatusColor } from './statusColor'
import { CreateClaimForm } from './CreateClaimForm'
import { useClaimsPage } from './useClaims'
import { PageHeading } from '../components/PageHeading'

// Roles allowed to create a claim (mirrors the backend gate; the server still enforces it).
const CREATE_ROLES = ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN']

// The statuses a claim can hold, for the filter dropdown (mirrors the ClaimStatus union).
const STATUSES: ClaimStatus[] = ['DRAFT', 'SUBMITTED', 'ACCEPTED', 'REJECTED', 'ADJUDICATED', 'CANCELLED']

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

/** Format a numeric amount as USD currency for display. */
export function money(amount: number): string {
  return amount.toLocaleString(undefined, { style: 'currency', currency: 'USD' })
}

export function ClaimsPage() {
  const { data: user } = useCurrentUser()
  const canCreate = (user?.roles ?? []).some((r) => CREATE_ROLES.includes(r))

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [status, setStatus] = useState<ClaimStatus | ''>('')
  // The raw search box, and the debounced value we actually query with (so we don't fire per keystroke).
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')

  // Debounce the search box: settle for 300ms after the last keystroke before querying the server.
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300)
    return () => clearTimeout(timer)
  }, [search])

  const claims = useClaimsPage({
    page,
    size,
    sort: undefined, // newest-first (the backend default); the card list has no column sort
    status: status || undefined,
    q: debouncedSearch.trim() || undefined,
  })
  const patients = usePatients()

  if (claims.isPending) return <LoadingScreen />
  if (claims.isError) return <ErrorScreen error={claims.error} />

  const nameFor = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'

  const rows = claims.data.content

  const historyCard = (
    <Card>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        {/* History header: title + count, then search + status filter */}
        <Stack
          direction="row"
          spacing={2}
          sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', rowGap: 1.5, mb: 2 }}
        >
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
              Claim history
            </Typography>
            <Chip
              label={claims.data.totalElements}
              size="small"
              sx={{ bgcolor: 'rgba(13,148,136,0.10)', color: 'primary.dark', fontWeight: 600 }}
            />
          </Stack>

          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1.5 }}>
            <TextField
              size="small"
              placeholder="Search claim #"
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
                htmlInput: { 'aria-label': 'Search claim #' },
              }}
              sx={{ minWidth: 200 }}
            />
            <TextField
              select
              label="Status"
              size="small"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value as ClaimStatus | '')
                setPage(0)
              }}
              sx={{ minWidth: 160 }}
            >
              <MenuItem value="">All statuses</MenuItem>
              {STATUSES.map((s) => (
                <MenuItem key={s} value={s}>
                  {s}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </Stack>

        {rows.length === 0 ? (
          <EmptyState message="No claims yet." />
        ) : (
          <Stack spacing={2}>
            {rows.map((c) => (
              <Box key={c.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: { xs: 2, sm: 2.5 } }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                  <Link component={RouterLink} to={`/claims/${c.id}`} sx={{ fontWeight: 700 }} underline="hover">
                    {c.claimNumber}
                  </Link>
                  <Chip label={c.status} size="small" color={claimStatusColor(c.status)} />
                </Stack>

                <Typography sx={{ fontWeight: 600, mt: 1 }}>{nameFor(c.patientId)}</Typography>

                <Divider sx={{ my: 2 }} />

                <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2 }}>
                  <Box>
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      Service date
                    </Typography>
                    <Typography>{c.serviceDate}</Typography>
                  </Box>
                  <Box>
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      Total charge
                    </Typography>
                    <Typography>{money(c.totalChargeAmount)}</Typography>
                  </Box>
                </Box>
              </Box>
            ))}
          </Stack>
        )}

        <TablePagination
          component="div"
          count={claims.data.totalElements}
          page={claims.data.page}
          onPageChange={(_, newPage) => setPage(newPage)}
          rowsPerPage={size}
          onRowsPerPageChange={(e) => {
            setSize(parseInt(e.target.value, 10))
            setPage(0)
          }}
          rowsPerPageOptions={ROWS_PER_PAGE_OPTIONS}
        />
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
        <PageHeading sx={{ mb: 0.5 }}>Claims</PageHeading>
        <Typography color="text.secondary">Create claims and keep track of their status.</Typography>
      </Box>

      {canCreate ? (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '3fr 2fr' }, gap: 3, alignItems: 'start' }}>
          <CreateClaimForm />
          {historyCard}
        </Box>
      ) : (
        historyCard
      )}
    </Stack>
  )
}

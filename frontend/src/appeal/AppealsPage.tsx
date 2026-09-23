import { useEffect, useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import {
  Avatar,
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
import ArrowForwardOutlinedIcon from '@mui/icons-material/ArrowForwardOutlined'
import type { AppealStatus } from '../api/types'
import { usePatients } from '../patients/usePatients'
import { useClaims } from '../claims/useClaims'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { EmptyState } from '../components/EmptyState'
import { appealStatusColor } from './statusColor'
import { CreateAppealForm } from './CreateAppealForm'
import { useAppeals } from './useAppeal'
import { PageHeading } from '../components/PageHeading'

// Roles allowed to submit an appeal (mirrors the backend gate; the server still enforces it).
const SUBMIT_ROLES = ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN']

// The statuses an appeal can hold, for the filter dropdown (mirrors the AppealStatus union).
const STATUSES: AppealStatus[] = ['SUBMITTED', 'UPHELD', 'OVERTURNED', 'WITHDRAWN']

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

/** Two-letter initials from a name, e.g. "Sam Sample" → "SS". */
function initials(name: string) {
  const parts = name.trim().split(/\s+/)
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || '?'
}

export function AppealsPage() {
  const { data: user } = useCurrentUser()
  const canSubmit = (user?.roles ?? []).some((r) => SUBMIT_ROLES.includes(r))

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [status, setStatus] = useState<AppealStatus | ''>('')
  // The raw search box, and the debounced value we actually query with (so we don't fire per keystroke).
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')

  // Debounce the search box: settle for 300ms after the last keystroke before querying the server.
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300)
    return () => clearTimeout(timer)
  }, [search])

  const appeals = useAppeals({
    page,
    size,
    sort: undefined, // newest-first (the backend default); the card list has no column sort
    status: status || undefined,
    q: debouncedSearch.trim() || undefined,
  })
  const patients = usePatients()
  const claims = useClaims()

  if (appeals.isPending) return <LoadingScreen />
  if (appeals.isError) return <ErrorScreen error={appeals.error} />

  const patientName = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'
  const claimNumber = (claimId: string) =>
    (claims.data ?? []).find((c) => c.id === claimId)?.claimNumber ?? '—'

  const rows = appeals.data.content

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
              Appeal history
            </Typography>
            <Chip
              label={appeals.data.totalElements}
              size="small"
              sx={{ bgcolor: 'rgba(13,148,136,0.10)', color: 'primary.dark', fontWeight: 600 }}
            />
          </Stack>

          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1.5 }}>
            <TextField
              size="small"
              placeholder="Search appeal #"
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
                htmlInput: { 'aria-label': 'Search appeal #' },
              }}
              sx={{ minWidth: 200 }}
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
          <EmptyState message="No appeals yet." />
        ) : (
          <Stack spacing={2}>
            {rows.map((a) => (
              <Box key={a.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: { xs: 2, sm: 2.5 } }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                  <Link component={RouterLink} to={`/appeals/${a.id}`} sx={{ fontWeight: 700 }} underline="hover">
                    {a.appealNumber}
                  </Link>
                  <Chip label={a.status} size="small" color={appealStatusColor(a.status)} />
                </Stack>

                <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mt: 1.5 }}>
                  <Avatar sx={{ width: 36, height: 36, bgcolor: 'rgba(13,148,136,0.12)', color: 'primary.dark', fontWeight: 700, fontSize: 13 }}>
                    {initials(patientName(a.patientId))}
                  </Avatar>
                  <Typography sx={{ fontWeight: 600 }}>{patientName(a.patientId)}</Typography>
                </Stack>

                <Box sx={{ mt: 2 }}>
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                    Claim
                  </Typography>
                  <Typography>{claimNumber(a.claimId)}</Typography>
                </Box>

                <Link
                  component={RouterLink}
                  to={`/appeals/${a.id}`}
                  underline="hover"
                  sx={{ mt: 2, display: 'inline-flex', alignItems: 'center', gap: 0.5, fontWeight: 600 }}
                >
                  View appeal
                  <ArrowForwardOutlinedIcon sx={{ fontSize: 16 }} />
                </Link>
              </Box>
            ))}
          </Stack>
        )}

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
        <PageHeading sx={{ mb: 0.5 }}>Appeals</PageHeading>
        <Typography color="text.secondary">Submit an appeal and track its status.</Typography>
      </Box>

      {canSubmit ? (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '2fr 3fr' }, gap: 3, alignItems: 'start' }}>
          <CreateAppealForm />
          {historyCard}
        </Box>
      ) : (
        historyCard
      )}
    </Stack>
  )
}

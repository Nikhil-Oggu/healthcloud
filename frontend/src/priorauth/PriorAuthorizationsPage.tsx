import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
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
import type { PriorAuthorizationStatus } from '../api/types'
import { usePatients } from '../patients/usePatients'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { EmptyState } from '../components/EmptyState'
import { priorAuthStatusColor } from './statusColor'
import { CreatePriorAuthForm } from './CreatePriorAuthForm'
import { usePriorAuthorizations } from './usePriorAuth'
import { PageHeading } from '../components/PageHeading'

// Roles allowed to request a prior auth (mirrors the backend gate; the server still enforces it).
const REQUEST_ROLES = ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN']

// The statuses a prior auth can hold, for the filter dropdown (mirrors the PriorAuthorizationStatus union).
const STATUSES: PriorAuthorizationStatus[] = ['REQUESTED', 'APPROVED', 'DENIED', 'CANCELLED']

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

/** Two-letter initials from a name, e.g. "Sam Sample" → "SS". */
function initials(name: string) {
  const parts = name.trim().split(/\s+/)
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || '?'
}

/** One label/value row inside an authorization card. */
function DetailRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: '130px 1fr', gap: 2 }}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2" component="div">
        {value}
      </Typography>
    </Box>
  )
}

export function PriorAuthorizationsPage() {
  const { data: user } = useCurrentUser()
  const canRequest = (user?.roles ?? []).some((r) => REQUEST_ROLES.includes(r))

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [status, setStatus] = useState<PriorAuthorizationStatus | ''>('')
  // The raw search box, and the debounced value we actually query with (so we don't fire per keystroke).
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')

  // Debounce the search box: settle for 300ms after the last keystroke before querying the server.
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300)
    return () => clearTimeout(timer)
  }, [search])

  const auths = usePriorAuthorizations({
    page,
    size,
    sort: undefined, // newest-first (the backend default); the card list has no column sort
    status: status || undefined,
    q: debouncedSearch.trim() || undefined,
  })
  const patients = usePatients()

  if (auths.isPending) return <LoadingScreen />
  if (auths.isError) return <ErrorScreen error={auths.error} />

  const nameFor = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'

  const rows = auths.data.content

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
              Authorization history
            </Typography>
            <Chip
              label={auths.data.totalElements}
              size="small"
              sx={{ bgcolor: 'rgba(13,148,136,0.10)', color: 'primary.dark', fontWeight: 600 }}
            />
          </Stack>

          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1.5 }}>
            <TextField
              size="small"
              placeholder="Search auth #"
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
                htmlInput: { 'aria-label': 'Search auth #' },
              }}
              sx={{ minWidth: 200 }}
            />
            <TextField
              select
              label="Status"
              size="small"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value as PriorAuthorizationStatus | '')
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
          <EmptyState message="No prior authorizations yet." />
        ) : (
          <Stack spacing={2}>
            {rows.map((a) => (
              <Box key={a.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: { xs: 2, sm: 2.5 } }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                  <Link component={RouterLink} to={`/prior-authorizations/${a.id}`} sx={{ fontWeight: 700 }} underline="hover">
                    {a.authNumber}
                  </Link>
                  <Chip label={a.status} size="small" color={priorAuthStatusColor(a.status)} />
                </Stack>

                <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mt: 1.5 }}>
                  <Avatar sx={{ width: 36, height: 36, bgcolor: 'rgba(13,148,136,0.12)', color: 'primary.dark', fontWeight: 700, fontSize: 13 }}>
                    {initials(nameFor(a.patientId))}
                  </Avatar>
                  <Typography sx={{ fontWeight: 600 }}>{nameFor(a.patientId)}</Typography>
                </Stack>

                <Divider sx={{ my: 2 }} />

                <Stack spacing={1}>
                  <DetailRow
                    label="Procedure"
                    value={
                      <>
                        {a.procedureCode}{' '}
                        <Typography component="span" variant="caption" color="text.secondary">
                          ({a.procedureCodeSystem})
                        </Typography>
                      </>
                    }
                  />
                  <DetailRow label="Requested from" value={a.requestedServiceFrom} />
                </Stack>

                <Link
                  component={RouterLink}
                  to={`/prior-authorizations/${a.id}`}
                  underline="hover"
                  sx={{ mt: 2, display: 'inline-flex', alignItems: 'center', gap: 0.5, fontWeight: 600 }}
                >
                  View request
                  <ArrowForwardOutlinedIcon sx={{ fontSize: 16 }} />
                </Link>
              </Box>
            ))}
          </Stack>
        )}

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
        <PageHeading sx={{ mb: 0.5 }}>Prior authorizations</PageHeading>
        <Typography color="text.secondary">Request authorization and track its status.</Typography>
      </Box>

      {canRequest ? (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '2fr 3fr' }, gap: 3, alignItems: 'start' }}>
          <CreatePriorAuthForm />
          {historyCard}
        </Box>
      ) : (
        historyCard
      )}
    </Stack>
  )
}

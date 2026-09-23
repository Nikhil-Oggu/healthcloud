import { useEffect, useState } from 'react'
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  InputAdornment,
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
  Tooltip,
  Typography,
} from '@mui/material'
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined'
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined'
import PlayArrowOutlinedIcon from '@mui/icons-material/PlayArrowOutlined'
import InboxOutlinedIcon from '@mui/icons-material/InboxOutlined'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import SearchOutlinedIcon from '@mui/icons-material/SearchOutlined'
import type { ReactNode } from 'react'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { useDeadLetterEvents, useReplayDeadLetter } from './useDeadLetter'
import { PageHeading } from '../components/PageHeading'

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

// The inspect → resolve → replay workflow, shown as numbered step cards above the table.
const STEPS: { num: string; icon: ReactNode; title: string; body: string }[] = [
  { num: '01', icon: <DescriptionOutlinedIcon />, title: 'Inspect', body: 'Review the failure and payload.' },
  { num: '02', icon: <SettingsOutlinedIcon />, title: 'Resolve', body: 'Fix the underlying cause.' },
  {
    num: '03',
    icon: <PlayArrowOutlinedIcon />,
    title: 'Replay',
    body: 'An administrator returns the message to its source topic.',
  },
]

/** Columns the backend allows sorting by (mirrors the controller allowlist). */
type SortField = 'createdAt' | 'sourceTopic'
type SortDir = 'asc' | 'desc'

/** A short, fixed-width prefix of an id/payload — the full value is shown in a tooltip. */
function short(value: string | null, max = 12): string {
  if (!value) return '—'
  return value.length <= max ? value : `${value.slice(0, max)}…`
}

export function DeadLetterEventsPage() {
  const { data: user } = useCurrentUser()
  const replay = useReplayDeadLetter()
  const [pendingId, setPendingId] = useState<string | null>(null)

  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
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
  const events = useDeadLetterEvents({ page, size, sort, q: debouncedSearch.trim() || undefined })

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

  if (events.isPending) return <LoadingScreen />
  if (events.isError) return <ErrorScreen error={events.error} />

  function onReplay(id: string) {
    replay.mutate(id, { onSettled: () => setPendingId(null) })
  }

  const rows = events.data.content

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
      {/* Breadcrumb */}
      <Box>
        <Typography variant="body2" color="text.secondary">
          {user?.organizationName ?? '—'}
          <Box component="span" sx={{ mx: 1, opacity: 0.6 }}>
            /
          </Box>
          Governance
        </Typography>
      </Box>

      {/* Title + subtitle */}
      <Box>
        <PageHeading sx={{ mb: 0.5 }}>Dead letters</PageHeading>
        <Typography color="text.secondary">Inspect failed messages and replay them after fixing the cause.</Typography>
      </Box>

      {replay.isError && <ErrorScreen error={replay.error} />}

      {/* Step cards */}
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' }, gap: 3 }}>
        {STEPS.map((s) => (
          <Card key={s.num}>
            <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
              <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start' }}>
                <Box
                  sx={{
                    flexShrink: 0,
                    width: 48,
                    height: 48,
                    borderRadius: '50%',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    bgcolor: 'rgba(13,148,136,0.10)',
                    color: 'primary.main',
                  }}
                >
                  {s.icon}
                </Box>
                <Box>
                  <Typography sx={{ fontWeight: 700 }}>
                    <Box component="span" sx={{ color: 'primary.main', mr: 0.75 }}>
                      {s.num}
                    </Box>
                    {s.title}
                  </Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                    {s.body}
                  </Typography>
                </Box>
              </Stack>
            </CardContent>
          </Card>
        ))}
      </Box>

      {/* Dead-lettered messages section */}
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
          Dead-lettered messages
        </Typography>
        <Chip
          label={events.data.totalElements}
          size="small"
          sx={{ bgcolor: 'rgba(13,148,136,0.10)', color: 'primary.dark', fontWeight: 600 }}
        />
      </Stack>

      <Card>
        <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
          <TextField
            size="small"
            placeholder="Search event ID / message key"
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
              htmlInput: { 'aria-label': 'Search event ID / message key' },
            }}
            sx={{ minWidth: 240, maxWidth: 360, mb: 2 }}
          />

          <TableContainer component={Paper} elevation={0}>
            <Table aria-label="Dead-letter events" size="small">
              <TableHead>
                <TableRow>
                  {sortableHeader('createdAt', 'When')}
                  {sortableHeader('sourceTopic', 'Source topic')}
                  <TableCell>Event ID</TableCell>
                  <TableCell>Failure</TableCell>
                  <TableCell>Payload</TableCell>
                  <TableCell align="right">Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={6}>
                      <Box
                        sx={{
                          display: 'flex',
                          flexDirection: 'column',
                          alignItems: 'center',
                          gap: 1,
                          py: 6,
                          color: 'text.secondary',
                          textAlign: 'center',
                        }}
                      >
                        <InboxOutlinedIcon sx={{ fontSize: 40, opacity: 0.5 }} />
                        <Typography variant="body2" sx={{ fontWeight: 600, color: 'text.primary' }}>
                          No dead-lettered messages.
                        </Typography>
                        <Typography variant="body2">Messages will appear here when available for inspection.</Typography>
                      </Box>
                    </TableCell>
                  </TableRow>
                ) : (
                  rows.map((e) => (
                    <TableRow key={e.id} hover>
                      <TableCell>
                        <Typography variant="caption" color="text.secondary">
                          {new Date(e.createdAt).toLocaleString()}
                        </Typography>
                      </TableCell>
                      <TableCell>{e.sourceTopic}</TableCell>
                      <TableCell>
                        <Tooltip title={e.eventId ?? ''}>
                          <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                            {short(e.eventId, 8)}
                          </Typography>
                        </Tooltip>
                      </TableCell>
                      <TableCell>
                        <Tooltip title={e.exceptionMessage ?? ''}>
                          <span>{e.exceptionType}</span>
                        </Tooltip>
                      </TableCell>
                      <TableCell>
                        <Tooltip title={e.payload}>
                          <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                            {short(e.payload, 24)}
                          </Typography>
                        </Tooltip>
                      </TableCell>
                      <TableCell align="right">
                        {e.replayedAt ? (
                          <Tooltip title={`Replayed ${new Date(e.replayedAt).toLocaleString()}`}>
                            <Chip label="Replayed" size="small" color="success" />
                          </Tooltip>
                        ) : pendingId === e.id ? (
                          <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
                            <Button
                              size="small"
                              variant="contained"
                              disabled={replay.isPending}
                              onClick={() => onReplay(e.id)}
                            >
                              {replay.isPending ? 'Replaying…' : 'Confirm'}
                            </Button>
                            <Button size="small" onClick={() => setPendingId(null)} disabled={replay.isPending}>
                              Cancel
                            </Button>
                          </Stack>
                        ) : (
                          <Button size="small" onClick={() => setPendingId(e.id)}>
                            Replay
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
            <TablePagination
              component="div"
              count={events.data.totalElements}
              page={events.data.page}
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

      {/* Footnote */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 1.5,
          p: 2,
          border: 1,
          borderColor: 'divider',
          borderRadius: 2,
          color: 'text.secondary',
        }}
      >
        <InfoOutlinedIcon fontSize="small" sx={{ mt: 0.25 }} />
        <Typography variant="body2">
          Messages that failed processing and were parked on a dead-letter topic, then drained here for inspection.
          Once the underlying cause is fixed, an administrator can replay one to re-drive it onto its source topic; the
          consumer is idempotent, so a replay of an already-processed event is harmless.
        </Typography>
      </Box>
    </Stack>
  )
}

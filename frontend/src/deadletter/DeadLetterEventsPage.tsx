import { useEffect, useState } from 'react'
import {
  Button,
  Chip,
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
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { EmptyState } from '../components/EmptyState'
import { useDeadLetterEvents, useReplayDeadLetter } from './useDeadLetter'
import { PageHeading } from '../components/PageHeading'

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

/** Columns the backend allows sorting by (mirrors the controller allowlist). */
type SortField = 'createdAt' | 'sourceTopic'
type SortDir = 'asc' | 'desc'

/** A short, fixed-width prefix of an id/payload — the full value is shown in a tooltip. */
function short(value: string | null, max = 12): string {
  if (!value) return '—'
  return value.length <= max ? value : `${value.slice(0, max)}…`
}

export function DeadLetterEventsPage() {
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
      <PageHeading>Dead letters</PageHeading>
      <Typography variant="body2" color="text.secondary">
        Messages that failed processing and were parked on a dead-letter topic, then drained here for inspection.
        Once the underlying cause is fixed, an administrator can replay one to re-drive it onto its source topic; the
        consumer is idempotent, so a replay of an already-processed event is harmless.
      </Typography>

      {replay.isError && <ErrorScreen error={replay.error} />}

      <TextField
        label="Search event id / message key"
        size="small"
        value={search}
        onChange={(e) => {
          setSearch(e.target.value)
          setPage(0)
        }}
        placeholder="paste an id"
        sx={{ maxWidth: 320 }}
      />

      <TableContainer component={Paper} variant="outlined">
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
                  <EmptyState message="No dead-lettered messages." />
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
    </Stack>
  )
}

import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Chip,
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
  Tooltip,
  Typography,
} from '@mui/material'
import type { AuditAction } from '../api/types'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { auditActionColor, auditOutcomeColor } from './statusColor'
import { useAuditEvents, useVerifyAuditChain } from './useAudit'

// The audit actions, for the server-side filter dropdown (mirrors the backend AuditAction enum).
const ACTIONS: { value: AuditAction; label: string }[] = [
  { value: 'CLAIM_ADJUDICATED', label: 'Claim adjudicated' },
  { value: 'CONSENT_REVOKED', label: 'Consent revoked' },
  { value: 'BREAK_GLASS_INVOKED', label: 'Break-glass invoked' },
  { value: 'BREAK_GLASS_REVOKED', label: 'Break-glass revoked' },
  { value: 'RETENTION_PURGED', label: 'Retention purged' },
  { value: 'DEAD_LETTER_REPLAYED', label: 'Dead-letter replayed' },
]

const ROWS_PER_PAGE_OPTIONS = [10, 20, 50]

/** Columns the backend allows sorting by; the rest are not sortable server-side. */
type SortField = 'occurredAt' | 'sequenceNo'
type SortDir = 'asc' | 'desc'

/** A short, fixed-width prefix of an id/hash — the full value is shown in a tooltip. */
function short(value: string | null): string {
  if (!value) return '—'
  return value.length <= 12 ? value : `${value.slice(0, 8)}…`
}

export function AuditEventsPage() {
  const verify = useVerifyAuditChain()
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [action, setAction] = useState<AuditAction | ''>('')
  // The raw search box, and the debounced value we actually query with (so we don't fire per keystroke).
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  // Default: newest first by occurredAt (the backend's default), no active column indicator.
  const [sortField, setSortField] = useState<SortField | null>(null)
  const [sortDir, setSortDir] = useState<SortDir>('asc')

  // Debounce the search box: settle for 300ms after the last keystroke before querying the server.
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300)
    return () => clearTimeout(timer)
  }, [search])

  const sort = sortField ? `${sortField},${sortDir}` : undefined
  const events = useAuditEvents({
    page,
    size,
    sort,
    action: action || undefined,
    q: debouncedSearch.trim() || undefined,
  })

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

  const rows = events.data.content
  const result = verify.data

  const sortableHeader = (field: SortField, label: string, align: 'left' | 'right' = 'left') => (
    <TableCell align={align} sortDirection={sortField === field ? sortDir : false}>
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
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="h5">Audit trail</Typography>
        <Button variant="contained" onClick={() => verify.mutate()} disabled={verify.isPending}>
          {verify.isPending ? 'Verifying…' : 'Verify integrity'}
        </Button>
      </Stack>

      {verify.isError && <ErrorScreen error={verify.error} />}
      {result && (
        <Alert severity={result.valid ? 'success' : 'error'}>
          {result.valid
            ? `Chain intact — ${result.entriesChecked} ${
                result.entriesChecked === 1 ? 'entry' : 'entries'
              } verified.`
            : `Tampering detected at sequence ${result.brokenAtSequence}${
                result.reason ? ` — ${result.reason}` : ''
              } (${result.entriesChecked} verified before the break).`}
        </Alert>
      )}

      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', rowGap: 2 }}>
        <TextField
          label="Search correlation / resource id"
          size="small"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }}
          placeholder="paste an id"
          sx={{ maxWidth: 320 }}
        />

        <TextField
          select
          size="small"
          label="Action"
          value={action}
          onChange={(e) => {
            setAction(e.target.value as AuditAction | '')
            setPage(0)
          }}
          sx={{ maxWidth: 260 }}
        >
          <MenuItem value="">All actions</MenuItem>
          {ACTIONS.map((a) => (
            <MenuItem key={a.value} value={a.value}>
              {a.label}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Audit events" size="small">
          <TableHead>
            <TableRow>
              {sortableHeader('occurredAt', 'When')}
              {sortableHeader('sequenceNo', 'Seq', 'right')}
              <TableCell>Action</TableCell>
              <TableCell>Resource</TableCell>
              <TableCell>Outcome</TableCell>
              <TableCell>Actor</TableCell>
              <TableCell>Detail</TableCell>
              <TableCell>Fingerprint</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={8}>
                  <Typography variant="body2" color="text.secondary">
                    No audit events{action === '' ? ' yet' : ' for this action'}.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              rows.map((e) => (
                <TableRow key={e.id} hover>
                  <TableCell>
                    <Typography variant="caption" color="text.secondary">
                      {new Date(e.occurredAt).toLocaleString()}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">{e.sequenceNo}</TableCell>
                  <TableCell>
                    <Chip label={e.action} size="small" color={auditActionColor(e.action)} />
                  </TableCell>
                  <TableCell>
                    <Tooltip title={e.resourceId ?? ''}>
                      <span>
                        {e.resourceType}
                        {e.resourceId ? ` · ${short(e.resourceId)}` : ''}
                      </span>
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    <Chip label={e.outcome} size="small" color={auditOutcomeColor(e.outcome)} />
                  </TableCell>
                  <TableCell>
                    <Tooltip title={e.actorUserId ?? 'system'}>
                      <span>{e.actorUserId ? short(e.actorUserId) : 'system'}</span>
                    </Tooltip>
                  </TableCell>
                  <TableCell>{e.detail}</TableCell>
                  <TableCell>
                    <Tooltip title={e.entryHash}>
                      <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                        {short(e.entryHash)}
                      </Typography>
                    </Tooltip>
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

      <Typography variant="caption" color="text.secondary">
        Each event is a link in a per-organization HMAC hash chain. “Verify integrity” asks the server to
        recompute the chain and detect any modified, deleted, reordered, inserted or truncated row.
      </Typography>
    </Stack>
  )
}

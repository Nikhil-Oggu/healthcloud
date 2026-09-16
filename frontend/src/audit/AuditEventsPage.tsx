import { useMemo, useState } from 'react'
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
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { auditActionColor, auditOutcomeColor } from './statusColor'
import { useAuditEvents, useVerifyAuditChain } from './useAudit'

/** A short, fixed-width prefix of an id/hash — the full value is shown in a tooltip. */
function short(value: string | null): string {
  if (!value) return '—'
  return value.length <= 12 ? value : `${value.slice(0, 8)}…`
}

export function AuditEventsPage() {
  const events = useAuditEvents()
  const verify = useVerifyAuditChain()
  const [actionFilter, setActionFilter] = useState('ALL')

  const rows = useMemo(() => {
    const all = events.data ?? []
    return actionFilter === 'ALL' ? all : all.filter((e) => e.action === actionFilter)
  }, [events.data, actionFilter])

  if (events.isPending) return <LoadingScreen />
  if (events.isError) return <ErrorScreen error={events.error} />

  const result = verify.data

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="h5">Audit trail</Typography>
        <Button
          variant="contained"
          onClick={() => verify.mutate()}
          disabled={verify.isPending}
        >
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

      <TextField
        select
        size="small"
        label="Action"
        value={actionFilter}
        onChange={(e) => setActionFilter(e.target.value)}
        sx={{ maxWidth: 260 }}
      >
        <MenuItem value="ALL">All actions</MenuItem>
        <MenuItem value="CLAIM_ADJUDICATED">Claim adjudicated</MenuItem>
        <MenuItem value="CONSENT_REVOKED">Consent revoked</MenuItem>
      </TextField>

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Audit events" size="small">
          <TableHead>
            <TableRow>
              <TableCell>When</TableCell>
              <TableCell align="right">Seq</TableCell>
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
                    No audit events{actionFilter === 'ALL' ? ' yet' : ' for this action'}.
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
      </TableContainer>

      <Typography variant="caption" color="text.secondary">
        Each event is a link in a per-organization HMAC hash chain. “Verify integrity” asks the server to
        recompute the chain and detect any modified, deleted, reordered, inserted or truncated row.
      </Typography>
    </Stack>
  )
}

import { useState } from 'react'
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
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { useDeadLetterEvents, useReplayDeadLetter } from './useDeadLetter'

/** A short, fixed-width prefix of an id/payload — the full value is shown in a tooltip. */
function short(value: string | null, max = 12): string {
  if (!value) return '—'
  return value.length <= max ? value : `${value.slice(0, max)}…`
}

export function DeadLetterEventsPage() {
  const events = useDeadLetterEvents()
  const replay = useReplayDeadLetter()
  const [pendingId, setPendingId] = useState<string | null>(null)

  if (events.isPending) return <LoadingScreen />
  if (events.isError) return <ErrorScreen error={events.error} />

  function onReplay(id: string) {
    replay.mutate(id, { onSettled: () => setPendingId(null) })
  }

  return (
    <Stack spacing={3}>
      <Typography variant="h5">Dead letters</Typography>
      <Typography variant="body2" color="text.secondary">
        Messages that failed processing and were parked on a dead-letter topic, then drained here for inspection.
        Once the underlying cause is fixed, an administrator can replay one to re-drive it onto its source topic; the
        consumer is idempotent, so a replay of an already-processed event is harmless.
      </Typography>

      {replay.isError && <ErrorScreen error={replay.error} />}

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Dead-letter events" size="small">
          <TableHead>
            <TableRow>
              <TableCell>When</TableCell>
              <TableCell>Source topic</TableCell>
              <TableCell>Event ID</TableCell>
              <TableCell>Failure</TableCell>
              <TableCell>Payload</TableCell>
              <TableCell align="right">Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {events.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6}>
                  <Typography variant="body2" color="text.secondary">
                    No dead-lettered messages.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              events.data.map((e) => (
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
      </TableContainer>
    </Stack>
  )
}

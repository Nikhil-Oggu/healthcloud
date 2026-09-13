import { useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router-dom'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  Link,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import type { ServiceRequestStatus } from '../api/types'
import { statusColor } from './statusColor'
import { actionLabel, allowedActions, reasonRequired } from './transitions'
import { useChangeStatus, useRequest, useRequestHistory } from './useRequests'

export function RequestDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const request = useRequest(id)
  const history = useRequestHistory(id)
  const changeStatus = useChangeStatus(id)

  const [pending, setPending] = useState<ServiceRequestStatus | null>(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  if (request.isPending) return <LoadingScreen />
  if (request.isError) return <ErrorScreen error={request.error} />

  const r = request.data
  const roles = user?.roles ?? []
  const actions = allowedActions(r.status, roles)

  async function apply(to: ServiceRequestStatus, withReason?: string) {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await changeStatus.mutateAsync({ targetStatus: to, expectedVersion: r.version, reason: withReason })
      setPending(null)
      setReason('')
    } catch (err) {
      if (err instanceof ApiClientError) {
        setActionError(err.message)
        setCorrelationId(err.correlationId)
      } else {
        setActionError('Could not update the request.')
      }
    }
  }

  function onAction(to: ServiceRequestStatus) {
    if (reasonRequired(to)) {
      setPending(to)
      setReason('')
    } else {
      void apply(to)
    }
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Link component={RouterLink} to="/requests">
          ← Back to requests
        </Link>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1 }}>
            <Typography variant="h5">{r.title}</Typography>
            <Chip label={r.status} color={statusColor(r.status)} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            {r.type} · {r.priority} priority
          </Typography>
          {r.description && <Typography sx={{ mt: 2 }}>{r.description}</Typography>}

          {actionError && (
            <Alert severity="error" sx={{ mt: 2 }} onClose={() => setActionError(null)}>
              {actionError}
              {correlationId && (
                <Typography variant="caption" sx={{ display: 'block', mt: 0.5, opacity: 0.8 }}>
                  Reference ID: {correlationId}
                </Typography>
              )}
            </Alert>
          )}

          {actions.length > 0 && (
            <>
              <Divider sx={{ my: 2 }} />
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
                {actions.map((to) => (
                  <Button
                    key={to}
                    variant="outlined"
                    size="small"
                    disabled={changeStatus.isPending}
                    onClick={() => onAction(to)}
                  >
                    {actionLabel(to)}
                  </Button>
                ))}
              </Stack>

              {pending && reasonRequired(pending) && (
                <Stack direction="row" spacing={1} sx={{ mt: 2, alignItems: 'flex-start' }}>
                  <TextField
                    label={`Reason to ${actionLabel(pending).toLowerCase()}`}
                    size="small"
                    fullWidth
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                  <Button
                    variant="contained"
                    size="small"
                    disabled={!reason.trim() || changeStatus.isPending}
                    onClick={() => void apply(pending, reason)}
                  >
                    Confirm
                  </Button>
                  <Button size="small" onClick={() => setPending(null)}>
                    Cancel
                  </Button>
                </Stack>
              )}
            </>
          )}
        </CardContent>
      </Card>

      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle1" gutterBottom>
            Timeline
          </Typography>
          {history.isPending ? (
            <Typography variant="body2" color="text.secondary">
              Loading…
            </Typography>
          ) : history.isError ? (
            <Typography variant="body2" color="error">
              Could not load the timeline.
            </Typography>
          ) : (
            <Stack spacing={1} divider={<Divider flexItem />}>
              {(history.data ?? []).map((h) => (
                <Box key={h.id}>
                  <Typography variant="body2">
                    {h.fromStatus ? `${h.fromStatus} → ${h.toStatus}` : `Created as ${h.toStatus}`}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {new Date(h.createdAt).toLocaleString()}
                    {h.reason ? ` · ${h.reason}` : ''}
                  </Typography>
                </Box>
              ))}
            </Stack>
          )}
        </CardContent>
      </Card>
    </Stack>
  )
}

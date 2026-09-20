import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { BackLink } from '../components/BackLink'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import type { PriorAuthorizationStatus } from '../api/types'
import { priorAuthStatusColor } from './statusColor'
import { actionLabel, allowedActions, reasonRequired } from './transitions'
import { usePriorAuthorization, usePriorAuthHistory, useChangePriorAuthStatus } from './usePriorAuth'
import { PageHeading } from '../components/PageHeading'

export function PriorAuthorizationDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const auth = usePriorAuthorization(id)
  const history = usePriorAuthHistory(id)
  const changeStatus = useChangePriorAuthStatus(id)

  const [pending, setPending] = useState<PriorAuthorizationStatus | null>(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  if (auth.isPending) return <LoadingScreen />
  if (auth.isError) return <ErrorScreen error={auth.error} />

  const a = auth.data
  const roles = user?.roles ?? []
  const actions = allowedActions(a.status, roles)

  async function apply(to: PriorAuthorizationStatus, withReason?: string) {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await changeStatus.mutateAsync({ targetStatus: to, expectedVersion: a.version, reason: withReason })
      setPending(null)
      setReason('')
    } catch (err) {
      reportError(err, 'Could not update the prior authorization.')
    }
  }

  function onAction(to: PriorAuthorizationStatus) {
    if (reasonRequired(to)) {
      setPending(to)
      setReason('')
    } else {
      void apply(to)
    }
  }

  function reportError(err: unknown, fallback: string) {
    if (err instanceof ApiClientError) {
      setActionError(err.message)
      setCorrelationId(err.correlationId)
    } else {
      setActionError(fallback)
    }
  }

  return (
    <Stack spacing={3}>
      <Box>
        <BackLink to="/prior-authorizations" label="Back to prior authorizations" />
      </Box>

      <Card>
        <CardContent>
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1 }}>
            <PageHeading>Prior auth {a.authNumber}</PageHeading>
            <Chip label={a.status} color={priorAuthStatusColor(a.status)} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            {a.procedureCode} ({a.procedureCodeSystem}) · {a.coveragePlanName ?? '—'} · service{' '}
            {a.requestedServiceFrom}
            {a.requestedServiceTo ? ` – ${a.requestedServiceTo}` : ' (open-ended)'}
          </Typography>
          {a.decisionReason && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              Decision reason: {a.decisionReason}
            </Typography>
          )}

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
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1, alignItems: 'center' }}>
                {actions.map((to) => (
                  <Button
                    key={to}
                    variant={to === 'APPROVED' ? 'contained' : 'outlined'}
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

      <Card>
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

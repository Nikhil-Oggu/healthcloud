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
import { useClaims } from '../claims/useClaims'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import type { AppealStatus } from '../api/types'
import { appealStatusColor } from './statusColor'
import { actionLabel, allowedActions, reasonRequired } from './transitions'
import { useAppeal, useAppealHistory, useChangeAppealStatus } from './useAppeal'
import { PageHeading } from '../components/PageHeading'

export function AppealDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const appeal = useAppeal(id)
  const history = useAppealHistory(id)
  const claims = useClaims()
  const changeStatus = useChangeAppealStatus(id)

  const [pending, setPending] = useState<AppealStatus | null>(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  if (appeal.isPending) return <LoadingScreen />
  if (appeal.isError) return <ErrorScreen error={appeal.error} />

  const a = appeal.data
  const roles = user?.roles ?? []
  const actions = allowedActions(a.status, roles)
  const claim = (claims.data ?? []).find((c) => c.id === a.claimId)

  async function apply(to: AppealStatus, withReason?: string) {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await changeStatus.mutateAsync({ targetStatus: to, expectedVersion: a.version, reason: withReason })
      setPending(null)
      setReason('')
    } catch (err) {
      reportError(err, 'Could not update the appeal.')
    }
  }

  function onAction(to: AppealStatus) {
    // Every appeal transition requires a reason, so always reveal the reason field first.
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
        <Link component={RouterLink} to="/appeals">
          ← Back to appeals
        </Link>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1 }}>
            <PageHeading>Appeal {a.appealNumber}</PageHeading>
            <Chip label={a.status} color={appealStatusColor(a.status)} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Disputing claim{' '}
            <Link component={RouterLink} to={`/claims/${a.claimId}`}>
              {claim?.claimNumber ?? a.claimId}
            </Link>
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            Reason: {a.reason}
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
                    variant={to === 'OVERTURNED' ? 'contained' : 'outlined'}
                    size="small"
                    disabled={changeStatus.isPending}
                    onClick={() => onAction(to)}
                  >
                    {actionLabel(to)}
                  </Button>
                ))}
              </Stack>

              {pending && (
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

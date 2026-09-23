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
import type { ReferralStatus } from '../api/types'
import { referralStatusColor } from './statusColor'
import { actionLabel, allowedActions, reasonRequired } from './transitions'
import { useReferral, useReferralHistory, useChangeReferralStatus } from './useReferral'
import { PageHeading } from '../components/PageHeading'

export function ReferralDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const referral = useReferral(id)
  const history = useReferralHistory(id)
  const changeStatus = useChangeReferralStatus(id)

  const [pending, setPending] = useState<ReferralStatus | null>(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  if (referral.isPending) return <LoadingScreen />
  if (referral.isError) return <ErrorScreen error={referral.error} />

  const r = referral.data
  const roles = user?.roles ?? []
  const actions = allowedActions(r.status, roles)

  async function apply(to: ReferralStatus, withReason?: string) {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await changeStatus.mutateAsync({ targetStatus: to, expectedVersion: r.version, reason: withReason })
      setPending(null)
      setReason('')
    } catch (err) {
      reportError(err, 'Could not update the referral.')
    }
  }

  function onAction(to: ReferralStatus) {
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
        <BackLink to="/referrals" label="Back to referrals" />
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          {user?.organizationName ?? '—'}
          <Box component="span" sx={{ mx: 1, opacity: 0.6 }}>
            /
          </Box>
          Care
        </Typography>
        <Divider sx={{ mt: 1.5 }} />
      </Box>

      <Box>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}>
          <PageHeading sx={{ mb: 0 }}>Referral {r.referralNumber}</PageHeading>
          <Chip label={r.status} color={referralStatusColor(r.status)} size="small" />
        </Stack>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          {r.specialty} · reason {r.reasonCode} ({r.reasonCodeSystem})
        </Typography>
        {r.decisionReason && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            Decision reason: {r.decisionReason}
          </Typography>
        )}
      </Box>

      {(actions.length > 0 || actionError) && (
      <Card>
        <CardContent>
          {actionError && (
            <Alert severity="error" onClose={() => setActionError(null)}>
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
              {actionError && <Divider sx={{ my: 2 }} />}
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
      )}

      <Card>
        <CardContent>
          <Typography variant="subtitle1" component="h2" gutterBottom sx={{ fontWeight: 700 }}>
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

import { useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router-dom'
import { BackLink } from '../components/BackLink'
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
import type { ClaimReviewStatus } from '../api/types'
import { claimReviewStatusColor } from './statusColor'
import { actionLabel, allowedActions } from './transitions'
import { useChangeClaimReviewStatus, useClaimReview, useClaimReviewHistory } from './useClaimReview'
import { PageHeading } from '../components/PageHeading'

export function ClaimReviewDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const review = useClaimReview(id)
  const history = useClaimReviewHistory(id)
  const claims = useClaims()
  const changeStatus = useChangeClaimReviewStatus(id)

  const [pending, setPending] = useState<ClaimReviewStatus | null>(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  if (review.isPending) return <LoadingScreen />
  if (review.isError) return <ErrorScreen error={review.error} />

  const r = review.data
  const roles = user?.roles ?? []
  const actions = allowedActions(r.status, roles)
  const claim = (claims.data ?? []).find((c) => c.id === r.claimId)

  async function apply(to: ClaimReviewStatus, withReason?: string) {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await changeStatus.mutateAsync({ targetStatus: to, expectedVersion: r.version, reason: withReason })
      setPending(null)
      setReason('')
    } catch (err) {
      reportError(err, 'Could not update the review.')
    }
  }

  function onAction(to: ClaimReviewStatus) {
    // Every review transition requires a reason, so always reveal the reason field first.
    setPending(to)
    setReason('')
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
        <BackLink to="/claim-reviews" label="Back to reviews" />
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          {user?.organizationName ?? '—'}
          <Box component="span" sx={{ mx: 1, opacity: 0.6 }}>
            /
          </Box>
          Claims &amp; coverage
        </Typography>
        <Divider sx={{ mt: 1.5 }} />
      </Box>

      <Box>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}>
          <PageHeading sx={{ mb: 0 }}>Review {r.reviewNumber}</PageHeading>
          <Chip label={r.status} color={claimReviewStatusColor(r.status)} size="small" />
        </Stack>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          Reviewing claim{' '}
          <Link component={RouterLink} to={`/claims/${r.claimId}`}>
            {claim?.claimNumber ?? r.claimId}
          </Link>
        </Typography>
        {r.reason && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            Reason: {r.reason}
          </Typography>
        )}
        {r.resolution && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            Resolution: {r.resolution}
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
                    variant={to === 'RESOLVED' ? 'contained' : 'outlined'}
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

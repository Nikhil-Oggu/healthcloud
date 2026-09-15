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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import type { ClaimStatus } from '../api/types'
import { claimStatusColor, lineOutcomeColor } from './statusColor'
import { actionLabel, allowedActions, canAdjudicate, reasonRequired } from './transitions'
import { money } from './ClaimsPage'
import {
  useAdjudicate,
  useAdjudication,
  useChangeClaimStatus,
  useClaim,
  useClaimHistory,
} from './useClaims'

export function ClaimDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const claim = useClaim(id)
  const history = useClaimHistory(id)
  const changeStatus = useChangeClaimStatus(id)
  const adjudicate = useAdjudicate(id)

  const [pending, setPending] = useState<ClaimStatus | null>(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  // Only fetch the adjudication once the claim has been adjudicated (else it 404s).
  const adjudication = useAdjudication(id, claim.data?.status === 'ADJUDICATED')

  if (claim.isPending) return <LoadingScreen />
  if (claim.isError) return <ErrorScreen error={claim.error} />

  const c = claim.data
  const roles = user?.roles ?? []
  const actions = allowedActions(c.status, roles)
  const showAdjudicate = canAdjudicate(c.status, roles)

  async function apply(to: ClaimStatus, withReason?: string) {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await changeStatus.mutateAsync({ targetStatus: to, expectedVersion: c.version, reason: withReason })
      setPending(null)
      setReason('')
    } catch (err) {
      reportError(err, 'Could not update the claim.')
    }
  }

  function onAction(to: ClaimStatus) {
    if (reasonRequired(to)) {
      setPending(to)
      setReason('')
    } else {
      void apply(to)
    }
  }

  async function onAdjudicate() {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await adjudicate.mutateAsync()
    } catch (err) {
      reportError(err, 'Could not adjudicate the claim.')
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
        <Link component={RouterLink} to="/claims">
          ← Back to claims
        </Link>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1 }}>
            <Typography variant="h5">Claim {c.claimNumber}</Typography>
            <Chip label={c.status} color={claimStatusColor(c.status)} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Service date {c.serviceDate} · total charge {money(c.totalChargeAmount)}
          </Typography>

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

          {(actions.length > 0 || showAdjudicate) && (
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
                {showAdjudicate && (
                  <Button
                    variant="contained"
                    size="small"
                    disabled={adjudicate.isPending}
                    onClick={() => void onAdjudicate()}
                  >
                    {adjudicate.isPending ? 'Adjudicating…' : 'Adjudicate'}
                  </Button>
                )}
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
            Lines
          </Typography>
          <Table size="small" aria-label="Claim lines">
            <TableHead>
              <TableRow>
                <TableCell>#</TableCell>
                <TableCell>Procedure</TableCell>
                <TableCell align="right">Units</TableCell>
                <TableCell align="right">Charge</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {c.lines.map((l) => (
                <TableRow key={l.id}>
                  <TableCell>{l.lineNumber}</TableCell>
                  <TableCell>
                    {l.procedureCode} <Typography component="span" variant="caption" color="text.secondary">({l.procedureCodeSystem})</Typography>
                  </TableCell>
                  <TableCell align="right">{l.units}</TableCell>
                  <TableCell align="right">{money(l.chargeAmount)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {c.status === 'ADJUDICATED' && <AdjudicationCard result={adjudication} />}

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

/** The explainable adjudication breakdown (§60): the plan that applied and how every amount was computed. */
function AdjudicationCard({ result }: { result: ReturnType<typeof useAdjudication> }) {
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Adjudication
        </Typography>

        {result.isPending ? (
          <Typography variant="body2" color="text.secondary">
            Loading…
          </Typography>
        ) : result.isError || !result.data ? (
          <Typography variant="body2" color="error">
            Could not load the adjudication.
          </Typography>
        ) : (
          <Stack spacing={2}>
            <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}>
              <Chip
                label={result.data.outcome}
                color={result.data.outcome === 'ADJUDICATED' ? 'success' : 'error'}
              />
              <Typography variant="body2" color="text.secondary">
                {result.data.coveragePlanName ?? 'No coverage applied'}
              </Typography>
            </Stack>

            <Box sx={{ overflowX: 'auto' }}>
              <Table size="small" aria-label="Adjudication breakdown">
                <TableHead>
                  <TableRow>
                    <TableCell>Procedure</TableCell>
                    <TableCell>Outcome</TableCell>
                    <TableCell align="right">Allowed</TableCell>
                    <TableCell align="right">Copay</TableCell>
                    <TableCell align="right">Deductible</TableCell>
                    <TableCell align="right">Coinsurance</TableCell>
                    <TableCell align="right">OOP-max</TableCell>
                    <TableCell align="right">Plan paid</TableCell>
                    <TableCell align="right">Member</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {result.data.lines.map((l) => (
                    <TableRow key={l.claimLineId}>
                      <TableCell>{l.procedureCode}</TableCell>
                      <TableCell>
                        <Chip label={l.outcome} size="small" color={lineOutcomeColor(l.outcome)} />
                      </TableCell>
                      <TableCell align="right">{money(l.allowedAmount)}</TableCell>
                      <TableCell align="right">{money(l.copayAmount)}</TableCell>
                      <TableCell align="right">{money(l.deductibleAppliedAmount)}</TableCell>
                      <TableCell align="right">{money(l.coinsuranceAmount)}</TableCell>
                      <TableCell align="right">{money(l.oopMaxAppliedAmount)}</TableCell>
                      <TableCell align="right">{money(l.planPaidAmount)}</TableCell>
                      <TableCell align="right">{money(l.memberResponsibility)}</TableCell>
                    </TableRow>
                  ))}
                  <TableRow>
                    <TableCell colSpan={6} />
                    <TableCell align="right">
                      <strong>Totals</strong>
                    </TableCell>
                    <TableCell align="right">
                      <strong>{money(result.data.totalPlanPaidAmount)}</strong>
                    </TableCell>
                    <TableCell align="right">
                      <strong>{money(result.data.totalMemberResponsibility)}</strong>
                    </TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </Box>
          </Stack>
        )}
      </CardContent>
    </Card>
  )
}

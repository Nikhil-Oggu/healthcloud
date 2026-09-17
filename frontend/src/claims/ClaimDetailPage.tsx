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
import { anomalySeverityColor, claimStatusColor, lineOutcomeColor } from './statusColor'
import { actionLabel, allowedActions, canAdjudicate, canReadjudicate, reasonRequired } from './transitions'
import { money } from './ClaimsPage'
import {
  useAdjudicate,
  useAdjudication,
  useAdjudicationVersions,
  useAnomalies,
  useChangeClaimStatus,
  useClaim,
  useClaimHistory,
  useScanAnomalies,
} from './useClaims'
import { DIRECTORY_ROLES, useProviders } from './useProviders'
import { PageHeading } from '../components/PageHeading'

export function ClaimDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const claim = useClaim(id)
  const history = useClaimHistory(id)
  const changeStatus = useChangeClaimStatus(id)
  const adjudicate = useAdjudicate(id)
  const anomalies = useAnomalies(id)
  const scan = useScanAnomalies(id)
  // The provider directory is gated to the claim-create roles; only fetch it for a caller who may read it, so a
  // PATIENT or CLAIMS_REVIEWER viewing a claim doesn't fire a doomed (403) request just to resolve a name.
  const canReadDirectory = DIRECTORY_ROLES.some((r) => (user?.roles ?? []).includes(r))
  const providers = useProviders(canReadDirectory)

  const [pending, setPending] = useState<ClaimStatus | null>(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  // Only fetch the adjudication (and its version history) once the claim has been adjudicated (else it 404s).
  const isAdjudicated = claim.data?.status === 'ADJUDICATED'
  const adjudication = useAdjudication(id, isAdjudicated)
  const versions = useAdjudicationVersions(id, isAdjudicated)

  if (claim.isPending) return <LoadingScreen />
  if (claim.isError) return <ErrorScreen error={claim.error} />

  const c = claim.data
  const roles = user?.roles ?? []
  // Resolve the rendering provider's id → display name (best-effort; the directory read may be gated for this role).
  const renderingProviderName = c.renderingProviderId
    ? providers.data?.find((p) => p.userId === c.renderingProviderId)?.fullName
    : undefined
  const actions = allowedActions(c.status, roles)
  const showAdjudicate = canAdjudicate(c.status, roles)
  const showReadjudicate = canReadjudicate(c.status, roles)
  const canScan = roles.includes('CLAIMS_REVIEWER') || roles.includes('ORG_ADMIN')

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

  async function onScan() {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await scan.mutateAsync()
    } catch (err) {
      reportError(err, 'Could not scan the claim.')
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
            <PageHeading>Claim {c.claimNumber}</PageHeading>
            <Chip label={c.status} color={claimStatusColor(c.status)} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Service date {c.serviceDate} · total charge {money(c.totalChargeAmount)}
            {c.renderingProviderId
              ? ` · rendered by ${renderingProviderName ?? 'a provider'}`
              : ''}
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

          {(actions.length > 0 || showAdjudicate || showReadjudicate) && (
            <>
              <Divider sx={{ my: 2 }} />
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1, alignItems: 'center' }}>
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
                {showReadjudicate && (
                  <>
                    <Button
                      variant="outlined"
                      size="small"
                      disabled={adjudicate.isPending}
                      onClick={() => void onAdjudicate()}
                    >
                      {adjudicate.isPending ? 'Re-adjudicating…' : 'Re-adjudicate'}
                    </Button>
                    <Typography variant="caption" color="text.secondary">
                      Re-runs the engine and records a new version.
                    </Typography>
                  </>
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

      {c.status === 'ADJUDICATED' && <VersionHistoryCard versions={versions} />}

      <AnomaliesCard
        result={anomalies}
        canScan={canScan}
        scanning={scan.isPending}
        onScan={() => void onScan()}
      />

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
  const version = result.data?.adjudicationVersion
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Adjudication{version ? ` — version ${version}` : ''}
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

/**
 * Advisory anomaly signals on the claim (§Phase 6 slice 11). Any caller who can reach the claim sees the current
 * signals; a reviewer/admin gets a Scan button that runs the detector (a rescan replaces the prior signals). The
 * signals never change the claim status or the adjudication math — they are advisory only.
 */
function AnomaliesCard({
  result,
  canScan,
  scanning,
  onScan,
}: {
  result: ReturnType<typeof useAnomalies>
  canScan: boolean
  scanning: boolean
  onScan: () => void
}) {
  const signals = result.data ?? []
  return (
    <Card variant="outlined">
      <CardContent>
        <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap', rowGap: 1 }}>
          <Typography variant="subtitle1">Anomaly signals</Typography>
          {canScan && (
            <Button variant="outlined" size="small" disabled={scanning} onClick={onScan}>
              {scanning ? 'Scanning…' : 'Scan'}
            </Button>
          )}
        </Stack>

        {result.isPending ? (
          <Typography variant="body2" color="text.secondary">
            Loading…
          </Typography>
        ) : result.isError ? (
          <Typography variant="body2" color="error">
            Could not load anomaly signals.
          </Typography>
        ) : signals.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {canScan ? 'No anomaly signals — run a scan to check this claim.' : 'No anomaly signals.'}
          </Typography>
        ) : (
          <Stack spacing={1} divider={<Divider flexItem />}>
            {signals.map((s) => (
              <Box key={s.id}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Chip label={s.severity} size="small" color={anomalySeverityColor(s.severity)} />
                  <Typography variant="body2">{s.signalType}</Typography>
                </Stack>
                <Typography variant="caption" color="text.secondary">
                  {s.detail} · {new Date(s.detectedAt).toLocaleString()}
                </Typography>
              </Box>
            ))}
          </Stack>
        )}
      </CardContent>
    </Card>
  )
}

/**
 * The re-adjudication history (§Phase 5 slice 11): every immutable version of this claim's adjudication, newest
 * first, as summary rows. The current (latest) version's full per-line breakdown is the Adjudication card above;
 * this card only appears once there is more than one version.
 */
function VersionHistoryCard({ versions }: { versions: ReturnType<typeof useAdjudicationVersions> }) {
  const data = versions.data ?? []
  if (versions.isPending || versions.isError || data.length <= 1) {
    return null
  }
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Version history
        </Typography>
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small" aria-label="Adjudication version history">
            <TableHead>
              <TableRow>
                <TableCell>Version</TableCell>
                <TableCell>Outcome</TableCell>
                <TableCell>Plan</TableCell>
                <TableCell align="right">Plan paid</TableCell>
                <TableCell align="right">Member</TableCell>
                <TableCell>Adjudicated</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.map((v) => (
                <TableRow key={v.id}>
                  <TableCell>{v.adjudicationVersion}</TableCell>
                  <TableCell>
                    <Chip
                      label={v.outcome}
                      size="small"
                      color={v.outcome === 'ADJUDICATED' ? 'success' : 'error'}
                    />
                  </TableCell>
                  <TableCell>{v.coveragePlanName ?? '—'}</TableCell>
                  <TableCell align="right">{money(v.totalPlanPaidAmount)}</TableCell>
                  <TableCell align="right">{money(v.totalMemberResponsibility)}</TableCell>
                  <TableCell>{new Date(v.adjudicatedAt).toLocaleString()}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      </CardContent>
    </Card>
  )
}

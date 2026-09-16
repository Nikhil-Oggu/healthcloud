import { useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router-dom'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
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
import { money } from '../claims/ClaimsPage'
import { MedicalCodePicker } from '../claims/MedicalCodePicker'
import { percent } from './CoveragePlansPage'
import {
  useAddExclusion,
  useAddFeeSchedule,
  useAddPriorAuthRequirement,
  useCoveragePlan,
  useExclusions,
  useFeeSchedule,
  usePriorAuthRequirements,
  useRemoveExclusion,
  useRemoveFeeSchedule,
  useRemovePriorAuthRequirement,
} from './useCoverage'

const ADMIN_ROLES = ['ORG_ADMIN']

export function CoveragePlanDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const plan = useCoveragePlan(id)
  const canAdmin = (user?.roles ?? []).some((r) => ADMIN_ROLES.includes(r))

  if (plan.isPending) return <LoadingScreen />
  if (plan.isError) return <ErrorScreen error={plan.error} />

  const p = plan.data

  return (
    <Stack spacing={3}>
      <Box>
        <Link component={RouterLink} to="/coverage-plans">
          ← Back to coverage plans
        </Link>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Typography variant="h5" gutterBottom>
            {p.name} <Typography component="span" color="text.secondary">({p.planCode})</Typography>
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {p.planType} · deductible {money(p.deductibleAmount)} · coinsurance {percent(p.coinsuranceRate)} ·
            copay {money(p.copayAmount)} · out-of-pocket max{' '}
            {p.outOfPocketMax == null ? 'none' : money(p.outOfPocketMax)}
          </Typography>
        </CardContent>
      </Card>

      <ExclusionsCard planId={id} canAdmin={canAdmin} />

      <FeeScheduleCard planId={id} canAdmin={canAdmin} />

      <PriorAuthRequirementsCard planId={id} canAdmin={canAdmin} />
    </Stack>
  )
}

/** A plan's excluded procedures, with add (via the code picker) / remove for ORG_ADMIN. */
function ExclusionsCard({ planId, canAdmin }: { planId: string; canAdmin: boolean }) {
  const exclusions = useExclusions(planId)
  const addExclusion = useAddExclusion(planId)
  const removeExclusion = useRemoveExclusion(planId)

  const [code, setCode] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  async function onAdd() {
    if (!code.trim()) return
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await addExclusion.mutateAsync({ procedureCode: code.trim() })
      setCode('')
    } catch (err) {
      reportError(err, 'Could not add the exclusion.')
    }
  }

  async function onRemove(exclusionId: string) {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await removeExclusion.mutateAsync(exclusionId)
    } catch (err) {
      reportError(err, 'Could not remove the exclusion.')
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
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Excluded procedures
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          A claim line billing an excluded procedure adjudicates as not covered (the member owes the charge).
        </Typography>

        {actionError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setActionError(null)}>
            {actionError}
            {correlationId && (
              <Typography variant="caption" sx={{ display: 'block', mt: 0.5, opacity: 0.8 }}>
                Reference ID: {correlationId}
              </Typography>
            )}
          </Alert>
        )}

        {exclusions.isPending ? (
          <Typography variant="body2" color="text.secondary">
            Loading…
          </Typography>
        ) : exclusions.isError ? (
          <Typography variant="body2" color="error">
            Could not load exclusions.
          </Typography>
        ) : exclusions.data.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No exclusions — the plan covers all procedures.
          </Typography>
        ) : (
          <Table size="small" aria-label="Excluded procedures">
            <TableHead>
              <TableRow>
                <TableCell>Code</TableCell>
                <TableCell>System</TableCell>
                {canAdmin && <TableCell align="right">Actions</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {exclusions.data.map((e) => (
                <TableRow key={e.id}>
                  <TableCell>{e.code}</TableCell>
                  <TableCell>{e.codeSystem}</TableCell>
                  {canAdmin && (
                    <TableCell align="right">
                      <Button
                        size="small"
                        color="error"
                        disabled={removeExclusion.isPending}
                        onClick={() => void onRemove(e.id)}
                      >
                        Remove
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}

        {canAdmin && (
          <>
            <Divider sx={{ my: 2 }} />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ alignItems: 'flex-start' }}>
              <MedicalCodePicker value={code} onChange={setCode} label="Exclude a procedure" />
              <Button variant="contained" size="small" disabled={!code.trim() || addExclusion.isPending} onClick={() => void onAdd()}>
                Add exclusion
              </Button>
            </Stack>
          </>
        )}
      </CardContent>
    </Card>
  )
}

/** A plan's fee schedule — the allowed amount per procedure — with add (code picker + amount) / remove for ORG_ADMIN. */
function FeeScheduleCard({ planId, canAdmin }: { planId: string; canAdmin: boolean }) {
  const feeSchedule = useFeeSchedule(planId)
  const addFeeSchedule = useAddFeeSchedule(planId)
  const removeFeeSchedule = useRemoveFeeSchedule(planId)

  const [code, setCode] = useState('')
  const [amount, setAmount] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  // The Add button is enabled only for a chosen code and a valid, non-negative amount (the backend re-validates).
  const amountValue = Number(amount)
  const amountValid = amount.trim() !== '' && Number.isFinite(amountValue) && amountValue >= 0

  async function onAdd() {
    if (!code.trim() || !amountValid) return
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await addFeeSchedule.mutateAsync({ procedureCode: code.trim(), allowedAmount: amountValue })
      setCode('')
      setAmount('')
    } catch (err) {
      reportError(err, 'Could not add the fee-schedule entry.')
    }
  }

  async function onRemove(entryId: string) {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await removeFeeSchedule.mutateAsync(entryId)
    } catch (err) {
      reportError(err, 'Could not remove the fee-schedule entry.')
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
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Fee schedule
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          A priced procedure is allowed the lesser of the billed charge and this amount; the difference is a
          provider write-off. An unpriced procedure is allowed at the billed charge.
        </Typography>

        {actionError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setActionError(null)}>
            {actionError}
            {correlationId && (
              <Typography variant="caption" sx={{ display: 'block', mt: 0.5, opacity: 0.8 }}>
                Reference ID: {correlationId}
              </Typography>
            )}
          </Alert>
        )}

        {feeSchedule.isPending ? (
          <Typography variant="body2" color="text.secondary">
            Loading…
          </Typography>
        ) : feeSchedule.isError ? (
          <Typography variant="body2" color="error">
            Could not load the fee schedule.
          </Typography>
        ) : feeSchedule.data.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No fee-schedule entries — priced procedures are allowed at the billed charge.
          </Typography>
        ) : (
          <Table size="small" aria-label="Fee schedule">
            <TableHead>
              <TableRow>
                <TableCell>Code</TableCell>
                <TableCell>System</TableCell>
                <TableCell align="right">Allowed</TableCell>
                {canAdmin && <TableCell align="right">Actions</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {feeSchedule.data.map((e) => (
                <TableRow key={e.id}>
                  <TableCell>{e.code}</TableCell>
                  <TableCell>{e.codeSystem}</TableCell>
                  <TableCell align="right">{money(e.allowedAmount)}</TableCell>
                  {canAdmin && (
                    <TableCell align="right">
                      <Button
                        size="small"
                        color="error"
                        disabled={removeFeeSchedule.isPending}
                        onClick={() => void onRemove(e.id)}
                      >
                        Remove
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}

        {canAdmin && (
          <>
            <Divider sx={{ my: 2 }} />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ alignItems: 'flex-start' }}>
              <MedicalCodePicker value={code} onChange={setCode} label="Price a procedure" />
              <TextField
                label="Allowed amount"
                type="number"
                size="small"
                slotProps={{ htmlInput: { step: '0.01', min: '0' } }}
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
              <Button
                variant="contained"
                size="small"
                disabled={!code.trim() || !amountValid || addFeeSchedule.isPending}
                onClick={() => void onAdd()}
              >
                Add entry
              </Button>
            </Stack>
          </>
        )}
      </CardContent>
    </Card>
  )
}

/** A plan's procedures that require prior authorization, with add (via the code picker) / remove for ORG_ADMIN. */
function PriorAuthRequirementsCard({ planId, canAdmin }: { planId: string; canAdmin: boolean }) {
  const requirements = usePriorAuthRequirements(planId)
  const addRequirement = useAddPriorAuthRequirement(planId)
  const removeRequirement = useRemovePriorAuthRequirement(planId)

  const [code, setCode] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  async function onAdd() {
    if (!code.trim()) return
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await addRequirement.mutateAsync({ procedureCode: code.trim() })
      setCode('')
    } catch (err) {
      reportError(err, 'Could not add the prior-auth requirement.')
    }
  }

  async function onRemove(requirementId: string) {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await removeRequirement.mutateAsync(requirementId)
    } catch (err) {
      reportError(err, 'Could not remove the prior-auth requirement.')
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
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Prior-auth requirements
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          A claim line billing one of these procedures adjudicates as needing prior authorization unless an
          approved authorization covers the service date.
        </Typography>

        {actionError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setActionError(null)}>
            {actionError}
            {correlationId && (
              <Typography variant="caption" sx={{ display: 'block', mt: 0.5, opacity: 0.8 }}>
                Reference ID: {correlationId}
              </Typography>
            )}
          </Alert>
        )}

        {requirements.isPending ? (
          <Typography variant="body2" color="text.secondary">
            Loading…
          </Typography>
        ) : requirements.isError ? (
          <Typography variant="body2" color="error">
            Could not load prior-auth requirements.
          </Typography>
        ) : requirements.data.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No prior-auth requirements — the plan covers procedures without pre-approval.
          </Typography>
        ) : (
          <Table size="small" aria-label="Prior-auth requirements">
            <TableHead>
              <TableRow>
                <TableCell>Code</TableCell>
                <TableCell>System</TableCell>
                {canAdmin && <TableCell align="right">Actions</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {requirements.data.map((r) => (
                <TableRow key={r.id}>
                  <TableCell>{r.code}</TableCell>
                  <TableCell>{r.codeSystem}</TableCell>
                  {canAdmin && (
                    <TableCell align="right">
                      <Button
                        size="small"
                        color="error"
                        disabled={removeRequirement.isPending}
                        onClick={() => void onRemove(r.id)}
                      >
                        Remove
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}

        {canAdmin && (
          <>
            <Divider sx={{ my: 2 }} />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ alignItems: 'flex-start' }}>
              <MedicalCodePicker value={code} onChange={setCode} label="Require prior auth for a procedure" />
              <Button
                variant="contained"
                size="small"
                disabled={!code.trim() || addRequirement.isPending}
                onClick={() => void onAdd()}
              >
                Add requirement
              </Button>
            </Stack>
          </>
        )}
      </CardContent>
    </Card>
  )
}

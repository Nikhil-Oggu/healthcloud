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
  Typography,
} from '@mui/material'
import { ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { money } from '../claims/ClaimsPage'
import { MedicalCodePicker } from '../claims/MedicalCodePicker'
import { percent } from './CoveragePlansPage'
import { useAddExclusion, useCoveragePlan, useExclusions, useRemoveExclusion } from './useCoverage'

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

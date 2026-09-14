import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Link as RouterLink, useParams } from 'react-router-dom'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Link,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import type {
  AssignMemberRequest,
  AssignmentCandidate,
  AssignmentStatus,
  ConsentDirective,
  ConsentStatus,
  RecordConsentRequest,
} from '../api/types'
import {
  useConsentDirectives,
  usePatient,
  useProviderAssignments,
  useRecordConsent,
  useRevokeConsent,
} from '../consent/useConsent'
import {
  useAssignCoordinator,
  useAssignProvider,
  useCoordinatorAssignments,
  useCoordinatorCandidates,
  useProviderCandidates,
  useRevokeCoordinatorAssignment,
  useRevokeProviderAssignment,
} from '../relationship/useAssignments'

// Roles allowed to record/revoke consent — mirrors the backend gate (the server still enforces it).
const WRITE_ROLES = ['CARE_COORDINATOR', 'ORG_ADMIN']

const PURPOSES = [
  'CARE_COORDINATION',
  'CLAIM_PROCESSING',
  'DOCUMENT_REVIEW',
  'APPOINTMENT_SUPPORT',
  'BENEFIT_SUPPORT',
] as const
const CATEGORIES = [
  'DEMOGRAPHICS_CONTACT',
  'CARE_COORDINATION',
  'CLINICAL_CONTEXT',
  'CLAIMS_BENEFITS',
  'DOCUMENTS',
] as const

// Mirrors the backend rule: a PROVIDER-scoped directive must name a provider; the others must not.
const recordSchema = z
  .object({
    effect: z.enum(['GRANT', 'DENY']),
    purpose: z.enum(PURPOSES),
    dataCategory: z.enum(CATEGORIES),
    scopeType: z.enum(['ORGANIZATION', 'CARE_TEAM', 'PROVIDER']),
    scopeRefId: z.string().optional(),
    effectiveFrom: z.string().optional(),
    effectiveTo: z.string().optional(),
  })
  .superRefine((v, ctx) => {
    if (v.scopeType === 'PROVIDER' && !v.scopeRefId) {
      ctx.addIssue({ code: 'custom', path: ['scopeRefId'], message: 'Choose a provider' })
    }
  })

type RecordForm = z.infer<typeof recordSchema>

const statusColor = (s: ConsentStatus): 'success' | 'error' | 'default' =>
  s === 'ACTIVE' ? 'success' : s === 'REVOKED' || s === 'EXPIRED' ? 'error' : 'default'

/**
 * A single patient with their consent directives (§22). Any same-tenant user may view; only
 * coordinators/admins see the record form and revoke actions (role-aware UI — the backend enforces it).
 */
export function PatientDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const patient = usePatient(id)
  const directives = useConsentDirectives(id)
  const canWrite = (user?.roles ?? []).some((r) => WRITE_ROLES.includes(r))

  if (patient.isPending) {
    return <LoadingScreen />
  }
  if (patient.isError) {
    return <ErrorScreen error={patient.error} />
  }

  const p = patient.data

  return (
    <Stack spacing={3}>
      <Box>
        <Link component={RouterLink} to="/patients" variant="body2">
          ← Patients
        </Link>
        <Typography variant="h5" sx={{ mt: 1 }}>
          {p.fullName}
        </Typography>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={4}>
            <Field label="MRN" value={p.medicalRecordNumber} />
            <Field
              label="Date of birth"
              value={
                p.dateOfBirth ?? (
                  <Typography component="span" variant="body2" color="text.secondary" sx={{ fontStyle: 'italic' }}>
                    Restricted
                  </Typography>
                )
              }
            />
            <Field label="Status" value={<Chip label={p.status} size="small" color={p.status === 'ACTIVE' ? 'success' : 'default'} />} />
          </Stack>
        </CardContent>
      </Card>

      <CareTeamCard patientId={id} canWrite={canWrite} />

      <Box>
        <Typography variant="h6" gutterBottom>
          Consent directives
        </Typography>

        {canWrite && <RecordDirectiveForm patientId={id} />}

        {directives.isError ? (
          <ErrorScreen error={directives.error} />
        ) : (
          <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
            <Table aria-label="Consent directives">
              <TableHead>
                <TableRow>
                  <TableCell>Effect</TableCell>
                  <TableCell>Purpose</TableCell>
                  <TableCell>Data category</TableCell>
                  <TableCell>Scope</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Effective</TableCell>
                  {canWrite && <TableCell align="right">Actions</TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                {(directives.data ?? []).length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={canWrite ? 7 : 6}>
                      <Typography variant="body2" color="text.secondary">
                        No consent directives yet — access falls back to deny-by-default.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  (directives.data ?? []).map((d) => (
                    <DirectiveRow key={d.id} patientId={id} directive={d} canWrite={canWrite} />
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Box>
    </Stack>
  )
}

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        {label}
      </Typography>
      <Typography component="span" variant="body1">
        {value}
      </Typography>
    </Box>
  )
}

// A care-team member, normalized across the provider and coordinator assignment tables.
interface TeamMember {
  assignmentId: string
  name: string
  status: AssignmentStatus
  expectedVersion: number
}

// Minimal structural shape of the assign/revoke mutations this component drives (avoids leaking the
// full TanStack Query generic signature into the props).
interface Mutation<V> {
  mutateAsync: (variables: V) => Promise<unknown>
  isPending: boolean
}

const memberStatusColor = (s: AssignmentStatus): 'success' | 'warning' | 'error' | 'default' =>
  s === 'ACTIVE' ? 'success' : s === 'PENDING' ? 'warning' : 'error'

/**
 * The patient's care team — providers and coordinators currently (ACTIVE/PENDING) assigned. These are the
 * relationships the object/relationship gate (§21 layer 6) and the CARE_TEAM consent scope (§22.5) consume,
 * so changing them here can flip a masked field. Any same-tenant user may view; only coordinators/admins
 * see the assign/revoke controls (role-aware UI — the backend still enforces it).
 */
function CareTeamCard({ patientId, canWrite }: { patientId: string; canWrite: boolean }) {
  const providers = useProviderAssignments(patientId)
  const coordinators = useCoordinatorAssignments(patientId)
  const providerCandidates = useProviderCandidates(patientId, canWrite)
  const coordinatorCandidates = useCoordinatorCandidates(patientId, canWrite)
  const assignProvider = useAssignProvider(patientId)
  const revokeProvider = useRevokeProviderAssignment(patientId)
  const assignCoordinator = useAssignCoordinator(patientId)
  const revokeCoordinator = useRevokeCoordinatorAssignment(patientId)

  const providerMembers: TeamMember[] = (providers.data ?? []).map((a) => ({
    assignmentId: a.id,
    name: a.providerName,
    status: a.status,
    expectedVersion: a.expectedVersion,
  }))
  const coordinatorMembers: TeamMember[] = (coordinators.data ?? []).map((a) => ({
    assignmentId: a.id,
    name: a.coordinatorName,
    status: a.status,
    expectedVersion: a.expectedVersion,
  }))

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        Care team
      </Typography>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ alignItems: 'stretch' }}>
        <AssignmentGroup
          label="Providers"
          noun="provider"
          members={providerMembers}
          candidates={providerCandidates.data ?? []}
          canWrite={canWrite}
          assign={assignProvider}
          revoke={revokeProvider}
        />
        <AssignmentGroup
          label="Coordinators"
          noun="coordinator"
          members={coordinatorMembers}
          candidates={coordinatorCandidates.data ?? []}
          canWrite={canWrite}
          assign={assignCoordinator}
          revoke={revokeCoordinator}
        />
      </Stack>
    </Box>
  )
}

function AssignmentGroup({
  label,
  noun,
  members,
  candidates,
  canWrite,
  assign,
  revoke,
}: {
  label: string
  noun: string
  members: TeamMember[]
  candidates: AssignmentCandidate[]
  canWrite: boolean
  assign: Mutation<AssignMemberRequest>
  revoke: Mutation<{ assignmentId: string; expectedVersion: number }>
}) {
  const [userId, setUserId] = useState('')
  const [effectiveFrom, setEffectiveFrom] = useState('')
  const [effectiveTo, setEffectiveTo] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function onAssign() {
    if (!userId) {
      setError(`Choose a ${noun} to assign.`)
      return
    }
    setError(null)
    try {
      await assign.mutateAsync({
        userId,
        effectiveFrom: effectiveFrom || undefined,
        effectiveTo: effectiveTo || undefined,
      })
      setUserId('')
      setEffectiveFrom('')
      setEffectiveTo('')
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : `Could not assign the ${noun}.`)
    }
  }

  async function onRevoke(m: TeamMember) {
    setError(null)
    try {
      await revoke.mutateAsync({ assignmentId: m.assignmentId, expectedVersion: m.expectedVersion })
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not revoke.')
    }
  }

  return (
    <Card variant="outlined" sx={{ flex: 1 }}>
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          {label}
        </Typography>

        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        {members.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            None assigned.
          </Typography>
        ) : (
          <Stack spacing={1}>
            {members.map((m) => (
              <Stack key={m.assignmentId} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                <Typography variant="body2" sx={{ flex: 1 }}>
                  {m.name}
                </Typography>
                <Chip label={m.status} size="small" color={memberStatusColor(m.status)} />
                {canWrite && (
                  <Button size="small" color="error" onClick={() => onRevoke(m)} disabled={revoke.isPending}>
                    Revoke
                  </Button>
                )}
              </Stack>
            ))}
          </Stack>
        )}

        {canWrite && (
          <Box sx={{ mt: 2 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ alignItems: 'flex-start' }}>
              <TextField
                select
                label={`Add ${noun}`}
                size="small"
                fullWidth
                slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
                value={userId}
                onChange={(e) => setUserId(e.target.value)}
              >
                <option value="">Select…</option>
                {candidates.map((c) => (
                  <option key={c.userId} value={c.userId}>
                    {c.fullName}
                  </option>
                ))}
              </TextField>
              <TextField
                label="From"
                type="date"
                size="small"
                slotProps={{ inputLabel: { shrink: true } }}
                value={effectiveFrom}
                onChange={(e) => setEffectiveFrom(e.target.value)}
              />
              <TextField
                label="To"
                type="date"
                size="small"
                slotProps={{ inputLabel: { shrink: true } }}
                value={effectiveTo}
                onChange={(e) => setEffectiveTo(e.target.value)}
              />
              <Button variant="contained" onClick={onAssign} disabled={assign.isPending || !userId}>
                {assign.isPending ? 'Assigning…' : 'Assign'}
              </Button>
            </Stack>
            {candidates.length === 0 && (
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                No one else available to assign.
              </Typography>
            )}
          </Box>
        )}
      </CardContent>
    </Card>
  )
}

function DirectiveRow({
  patientId,
  directive,
  canWrite,
}: {
  patientId: string
  directive: ConsentDirective
  canWrite: boolean
}) {
  const revoke = useRevokeConsent(patientId)
  const [error, setError] = useState<string | null>(null)
  const isCurrent = directive.status === 'ACTIVE' || directive.status === 'SCHEDULED'

  async function onRevoke() {
    setError(null)
    try {
      await revoke.mutateAsync({ directiveId: directive.id, expectedVersion: directive.expectedVersion })
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not revoke.')
    }
  }

  return (
    <TableRow>
      <TableCell>
        <Chip
          label={directive.effect}
          size="small"
          color={directive.effect === 'GRANT' ? 'success' : 'error'}
          variant="outlined"
        />
      </TableCell>
      <TableCell>{directive.purpose}</TableCell>
      <TableCell>{directive.dataCategory}</TableCell>
      <TableCell>{directive.scopeType}</TableCell>
      <TableCell>
        <Chip label={directive.status} size="small" color={statusColor(directive.status)} />
      </TableCell>
      <TableCell>
        {directive.effectiveFrom}
        {directive.effectiveTo ? ` → ${directive.effectiveTo}` : ''}
      </TableCell>
      {canWrite && (
        <TableCell align="right">
          {isCurrent && (
            <Button size="small" color="error" onClick={onRevoke} disabled={revoke.isPending}>
              {revoke.isPending ? 'Revoking…' : 'Revoke'}
            </Button>
          )}
          {error && (
            <Typography variant="caption" color="error" sx={{ display: 'block' }}>
              {error}
            </Typography>
          )}
        </TableCell>
      )}
    </TableRow>
  )
}

function RecordDirectiveForm({ patientId }: { patientId: string }) {
  const record = useRecordConsent(patientId)
  const providerAssignments = useProviderAssignments(patientId)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  const {
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors },
  } = useForm<RecordForm>({
    resolver: zodResolver(recordSchema),
    defaultValues: {
      effect: 'GRANT',
      purpose: 'CARE_COORDINATION',
      dataCategory: 'DEMOGRAPHICS_CONTACT',
      scopeType: 'ORGANIZATION',
      scopeRefId: '',
      effectiveFrom: '',
      effectiveTo: '',
    },
  })

  const scopeType = watch('scopeType')

  async function onSubmit(values: RecordForm) {
    setSubmitError(null)
    setCorrelationId(undefined)
    const body: RecordConsentRequest = {
      effect: values.effect,
      purpose: values.purpose,
      dataCategory: values.dataCategory,
      scopeType: values.scopeType,
      scopeRefId: values.scopeType === 'PROVIDER' ? values.scopeRefId : undefined,
      effectiveFrom: values.effectiveFrom || undefined,
      effectiveTo: values.effectiveTo || undefined,
    }
    try {
      await record.mutateAsync(body)
      reset()
    } catch (err) {
      if (err instanceof ApiClientError) {
        setSubmitError(err.message)
        setCorrelationId(err.correlationId)
      } else {
        setSubmitError('Could not record the directive. Please try again.')
      }
    }
  }

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Record directive
        </Typography>

        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
            {correlationId && (
              <Typography variant="caption" sx={{ display: 'block', mt: 0.5, opacity: 0.8 }}>
                Reference ID: {correlationId}
              </Typography>
            )}
          </Alert>
        )}

        <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack spacing={2}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField select label="Effect" size="small" fullWidth slotProps={{ select: { native: true } }} {...register('effect')}>
                <option value="GRANT">GRANT</option>
                <option value="DENY">DENY</option>
              </TextField>
              <TextField select label="Purpose" size="small" fullWidth slotProps={{ select: { native: true } }} {...register('purpose')}>
                {PURPOSES.map((x) => (
                  <option key={x} value={x}>
                    {x}
                  </option>
                ))}
              </TextField>
              <TextField select label="Data category" size="small" fullWidth slotProps={{ select: { native: true } }} {...register('dataCategory')}>
                {CATEGORIES.map((x) => (
                  <option key={x} value={x}>
                    {x}
                  </option>
                ))}
              </TextField>
            </Stack>

            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: 'flex-start' }}>
              <TextField select label="Scope" size="small" fullWidth slotProps={{ select: { native: true } }} {...register('scopeType')}>
                <option value="ORGANIZATION">ORGANIZATION</option>
                <option value="CARE_TEAM">CARE_TEAM</option>
                <option value="PROVIDER">PROVIDER</option>
              </TextField>
              {scopeType === 'PROVIDER' && (
                <TextField
                  select
                  label="Provider"
                  size="small"
                  fullWidth
                  slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
                  error={!!errors.scopeRefId}
                  helperText={errors.scopeRefId?.message ?? 'Assigned providers only'}
                  {...register('scopeRefId')}
                >
                  <option value="">Select a provider…</option>
                  {(providerAssignments.data ?? [])
                    .filter((a) => a.status === 'ACTIVE')
                    .map((a) => (
                      <option key={a.providerUserId} value={a.providerUserId}>
                        {a.providerName}
                      </option>
                    ))}
                </TextField>
              )}
              <TextField
                label="Effective from"
                type="date"
                size="small"
                fullWidth
                slotProps={{ inputLabel: { shrink: true } }}
                {...register('effectiveFrom')}
              />
              <TextField
                label="Effective to"
                type="date"
                size="small"
                fullWidth
                slotProps={{ inputLabel: { shrink: true } }}
                {...register('effectiveTo')}
              />
            </Stack>

            <Box>
              <Button type="submit" variant="contained" disabled={record.isPending}>
                {record.isPending ? 'Recording…' : 'Record'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

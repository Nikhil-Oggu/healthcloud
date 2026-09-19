import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useParams } from 'react-router-dom'
import { BackLink } from '../components/BackLink'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
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
import { api, ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { BreakGlassPanel } from '../breakglass/BreakGlassPanel'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import type {
  AssignMemberRequest,
  AssignmentCandidate,
  AssignmentStatus,
  ConsentDirective,
  ConsentStatus,
  DocumentScanStatus,
  PatientDocument,
  RecordConsentRequest,
} from '../api/types'
import { useDocuments, useUploadDocument } from '../documents/useDocuments'
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
import { useCoveragePlans } from '../coverage/useCoverage'
import { useEligibility, useEnrollEligibility } from '../coverage/useEligibility'
import { PageHeading } from '../components/PageHeading'

// Role-aware UI mirrors the backend gates (the server still enforces them):
//  - consent: staff OR a PATIENT for their OWN record (self-service);
//  - care team: staff only (a patient never manages the care team).
const CONSENT_WRITE_ROLES = ['PATIENT', 'CARE_COORDINATOR', 'ORG_ADMIN']
const CARE_TEAM_WRITE_ROLES = ['CARE_COORDINATOR', 'ORG_ADMIN']
//  - documents: staff OR a PATIENT for their OWN record may upload (same rule the backend enforces).
const DOCUMENT_WRITE_ROLES = ['PATIENT', 'CARE_COORDINATOR', 'ORG_ADMIN']
//  - eligibility: staff only enroll a patient in a plan (a patient never enrolls themselves).
const ELIGIBILITY_WRITE_ROLES = ['CARE_COORDINATOR', 'ORG_ADMIN']

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
 * A single patient with their consent directives (§22) and care team. Any same-tenant user with access may
 * view; consent record/revoke is shown to staff and to a patient on their own record, while care-team
 * assign/revoke is staff-only (role-aware UI — the backend enforces both).
 */
export function PatientDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const patient = usePatient(id)
  const directives = useConsentDirectives(id)
  const roles = user?.roles ?? []
  const canManageConsent = roles.some((r) => CONSENT_WRITE_ROLES.includes(r))
  const canManageCareTeam = roles.some((r) => CARE_TEAM_WRITE_ROLES.includes(r))
  const canUploadDocuments = roles.some((r) => DOCUMENT_WRITE_ROLES.includes(r))
  const canManageEligibility = roles.some((r) => ELIGIBILITY_WRITE_ROLES.includes(r))

  if (patient.isPending) {
    return <LoadingScreen />
  }
  if (patient.isError) {
    // A PROVIDER who reaches a patient they are not assigned to gets a secure 404. Offer break-glass right here —
    // the patient id is already in the URL — instead of the generic error screen (§Phase 7 slice 5).
    const notFound = patient.error instanceof ApiClientError && patient.error.status === 404
    if (notFound && roles.includes('PROVIDER')) {
      return <BreakGlassPanel patientId={id} />
    }
    return <ErrorScreen error={patient.error} />
  }

  const p = patient.data

  return (
    <Stack spacing={3}>
      <Box>
        <BackLink to="/patients" label="Back to patients" />
        <PageHeading sx={{ mt: 1 }}>
          {p.fullName}
        </PageHeading>
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

      <CareTeamCard patientId={id} canWrite={canManageCareTeam} />

      <EligibilityCard patientId={id} canWrite={canManageEligibility} />

      <DocumentsCard patientId={id} canWrite={canUploadDocuments} />

      <Box>
        <Typography variant="h6" gutterBottom>
          Consent directives
        </Typography>

        {canManageConsent && <RecordDirectiveForm patientId={id} />}

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
                  {canManageConsent && <TableCell align="right">Actions</TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                {(directives.data ?? []).length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={canManageConsent ? 7 : 6}>
                      <Typography variant="body2" color="text.secondary">
                        No consent directives yet — access falls back to deny-by-default.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  (directives.data ?? []).map((d) => (
                    <DirectiveRow key={d.id} patientId={id} directive={d} canWrite={canManageConsent} />
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

/**
 * A patient's coverage eligibility (§Phase 4/5) — the enrollments the adjudication engine reads to find the
 * plan in effect on a claim's service date. Any same-tenant user with access to the patient may view; enrolling
 * is shown to CARE_COORDINATOR/ORG_ADMIN (role-aware UI — the backend enforces it).
 */
function EligibilityCard({ patientId, canWrite }: { patientId: string; canWrite: boolean }) {
  const eligibility = useEligibility(patientId)

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        Coverage eligibility
      </Typography>

      {canWrite && <EnrollEligibilityForm patientId={patientId} />}

      {eligibility.isError ? (
        <ErrorScreen error={eligibility.error} />
      ) : (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 2 }}>
          <Table aria-label="Coverage eligibility">
            <TableHead>
              <TableRow>
                <TableCell>Plan</TableCell>
                <TableCell>Member ID</TableCell>
                <TableCell>Effective from</TableCell>
                <TableCell>Effective to</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(eligibility.data ?? []).length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4}>
                    <Typography variant="body2" color="text.secondary">
                      Not enrolled in any coverage plan yet.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                (eligibility.data ?? []).map((e) => (
                  <TableRow key={e.id} hover>
                    <TableCell>{e.coveragePlanName}</TableCell>
                    <TableCell>{e.memberId}</TableCell>
                    <TableCell>{e.effectiveFrom}</TableCell>
                    <TableCell>{e.effectiveTo ?? 'Open-ended'}</TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  )
}

// Mirrors the backend Jakarta rules on EnrollEligibilityRequest (coveragePlanId + memberId + effectiveFrom
// required; effectiveTo optional/open-ended). The in-tenant-plan (400) and non-overlap (409) checks are the
// server's — surfaced from ApiClientError.
const enrollSchema = z.object({
  coveragePlanId: z.string().min(1, 'Choose a plan'),
  memberId: z.string().trim().min(1, 'Required').max(64, 'At most 64 characters'),
  effectiveFrom: z.string().min(1, 'Required'),
  effectiveTo: z.string().optional(),
})
type EnrollForm = z.infer<typeof enrollSchema>

function EnrollEligibilityForm({ patientId }: { patientId: string }) {
  const plans = useCoveragePlans()
  const enroll = useEnrollEligibility(patientId)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<EnrollForm>({
    resolver: zodResolver(enrollSchema),
    defaultValues: { coveragePlanId: '', memberId: '', effectiveFrom: '', effectiveTo: '' },
  })

  async function onSubmit(values: EnrollForm) {
    setSubmitError(null)
    try {
      await enroll.mutateAsync({
        coveragePlanId: values.coveragePlanId,
        memberId: values.memberId,
        effectiveFrom: values.effectiveFrom,
        effectiveTo: values.effectiveTo || undefined,
      })
      reset()
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'Could not enroll the patient.')
    }
  }

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Enroll in a plan
        </Typography>
        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
          </Alert>
        )}
        <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: 'flex-start' }}>
            <TextField
              select label="Plan" size="small" fullWidth
              slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
              error={!!errors.coveragePlanId} helperText={errors.coveragePlanId?.message}
              defaultValue=""
              {...register('coveragePlanId')}
            >
              <option value="">Select…</option>
              {(plans.data ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} ({p.planCode})
                </option>
              ))}
            </TextField>
            <TextField
              label="Member ID" size="small" fullWidth
              {...register('memberId')} error={!!errors.memberId} helperText={errors.memberId?.message}
            />
            <TextField
              label="Coverage start" type="date" size="small"
              slotProps={{ inputLabel: { shrink: true } }}
              {...register('effectiveFrom')} error={!!errors.effectiveFrom}
              helperText={errors.effectiveFrom?.message}
            />
            <TextField
              label="Coverage end (optional)" type="date" size="small"
              slotProps={{ inputLabel: { shrink: true } }}
              {...register('effectiveTo')} error={!!errors.effectiveTo}
              helperText={errors.effectiveTo?.message}
            />
            <Button type="submit" variant="contained" disabled={enroll.isPending}>
              {enroll.isPending ? 'Enrolling…' : 'Enroll'}
            </Button>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

const scanStatusColor = (s: DocumentScanStatus): 'success' | 'warning' | 'error' =>
  s === 'CLEAN' ? 'success' : s === 'PENDING' ? 'warning' : 'error'

/** Human-readable byte size (synthetic files are small, so B/KB/MB is enough). */
function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * The patient's documents (§19). Any same-tenant user who can reach the patient may view the list and
 * download a CLEAN file; only document write roles (staff, or a patient on their own record) see the upload
 * control — role-aware UI, the backend enforces it. A QUARANTINED or still-PENDING document shows its status
 * and offers no download (the backend would withhold it with a 409 anyway).
 */
function DocumentsCard({ patientId, canWrite }: { patientId: string; canWrite: boolean }) {
  const documents = useDocuments(patientId)
  const upload = useUploadDocument(patientId)
  const [file, setFile] = useState<File | null>(null)
  const [inputKey, setInputKey] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [downloadingId, setDownloadingId] = useState<string | null>(null)

  async function onUpload() {
    if (!file) {
      return
    }
    setError(null)
    try {
      await upload.mutateAsync(file)
      setFile(null)
      setInputKey((k) => k + 1) // remount the file input to clear the chosen file
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not upload the document.')
    }
  }

  async function onDownload(doc: PatientDocument) {
    setError(null)
    setDownloadingId(doc.id)
    try {
      const blob = await api.downloadDocument(patientId, doc.id)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = doc.fileName
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : 'Could not download the document.')
    } finally {
      setDownloadingId(null)
    }
  }

  const rows = documents.data ?? []

  return (
    <Box>
      <Typography variant="h6" gutterBottom>
        Documents
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {canWrite && (
        <Card variant="outlined" sx={{ mb: 2 }}>
          <CardContent>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: 'center' }}>
              <input
                key={inputKey}
                type="file"
                aria-label="Choose a document"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
              <Button variant="contained" onClick={onUpload} disabled={!file || upload.isPending}>
                {upload.isPending ? 'Uploading…' : 'Upload'}
              </Button>
            </Stack>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
              Allowed: PDF, PNG, JPEG, GIF, text, CSV. Uploads are malware-scanned before they can be downloaded.
            </Typography>
          </CardContent>
        </Card>
      )}

      {documents.isError ? (
        <ErrorScreen error={documents.error} />
      ) : (
        <TableContainer component={Paper} variant="outlined">
          <Table aria-label="Documents">
            <TableHead>
              <TableRow>
                <TableCell>File</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Size</TableCell>
                <TableCell>Scan</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5}>
                    <Typography variant="body2" color="text.secondary">
                      No documents yet.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                rows.map((doc) => (
                  <TableRow key={doc.id}>
                    <TableCell>{doc.fileName}</TableCell>
                    <TableCell>{doc.contentType}</TableCell>
                    <TableCell>{formatBytes(doc.sizeBytes)}</TableCell>
                    <TableCell>
                      <Chip label={doc.scanStatus} size="small" color={scanStatusColor(doc.scanStatus)} />
                    </TableCell>
                    <TableCell align="right">
                      {doc.scanStatus === 'CLEAN' ? (
                        <Button
                          size="small"
                          onClick={() => onDownload(doc)}
                          disabled={downloadingId === doc.id}
                        >
                          {downloadingId === doc.id ? 'Downloading…' : 'Download'}
                        </Button>
                      ) : (
                        <Typography variant="caption" color="text.secondary">
                          {doc.scanStatus === 'QUARANTINED' ? 'Quarantined' : 'Scanning…'}
                        </Typography>
                      )}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
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

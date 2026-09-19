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
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import type { ServiceRequestStatus } from '../api/types'
import { statusColor } from './statusColor'
import { actionLabel, allowedActions, reasonRequired } from './transitions'
import {
  useAddComment,
  useAssign,
  useAssignableUsers,
  useAssignment,
  useChangeStatus,
  useComments,
  useRequest,
  useRequestHistory,
} from './useRequests'
import { PageHeading } from '../components/PageHeading'

// Participant roles that may comment — mirrors the backend gate (server still enforces it).
const COMMENT_ROLES = ['PATIENT', 'PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN']
// Roles that may assign — mirrors the backend gate (server still enforces it).
const ASSIGN_ROLES = ['CARE_COORDINATOR', 'ORG_ADMIN']

export function RequestDetailPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const request = useRequest(id)
  const history = useRequestHistory(id)
  const changeStatus = useChangeStatus(id)

  const [pending, setPending] = useState<ServiceRequestStatus | null>(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  if (request.isPending) return <LoadingScreen />
  if (request.isError) return <ErrorScreen error={request.error} />

  const r = request.data
  const roles = user?.roles ?? []
  const actions = allowedActions(r.status, roles)

  async function apply(to: ServiceRequestStatus, withReason?: string) {
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await changeStatus.mutateAsync({ targetStatus: to, expectedVersion: r.version, reason: withReason })
      setPending(null)
      setReason('')
    } catch (err) {
      if (err instanceof ApiClientError) {
        setActionError(err.message)
        setCorrelationId(err.correlationId)
      } else {
        setActionError('Could not update the request.')
      }
    }
  }

  function onAction(to: ServiceRequestStatus) {
    if (reasonRequired(to)) {
      setPending(to)
      setReason('')
    } else {
      void apply(to)
    }
  }

  return (
    <Stack spacing={3}>
      <Box>
        <BackLink to="/requests" label="Back to requests" />
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1 }}>
            <PageHeading>{r.title}</PageHeading>
            <Chip label={r.status} color={statusColor(r.status)} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            {r.type} · {r.priority} priority
          </Typography>
          {r.description && <Typography sx={{ mt: 2 }}>{r.description}</Typography>}

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

      <AssignmentCard
        requestId={id}
        requestVersion={r.version}
        status={r.status}
        canAssign={roles.some((rr) => ASSIGN_ROLES.includes(rr))}
      />

      <CommentsCard requestId={id} canComment={roles.some((r) => COMMENT_ROLES.includes(r))} />
    </Stack>
  )
}

// Assignment is only meaningful once a request has been triaged (backend enforces this too).
const ASSIGNABLE_STATUSES: ServiceRequestStatus[] = ['TRIAGED', 'ASSIGNED']

/**
 * The request's current assignee, plus an assign/reassign selector for coordinators/admins. Assigning
 * a TRIAGED request advances it to ASSIGNED on the backend; the role gate here is UI convenience only.
 */
function AssignmentCard({
  requestId,
  requestVersion,
  status,
  canAssign,
}: {
  requestId: string
  requestVersion: number
  status: ServiceRequestStatus
  canAssign: boolean
}) {
  const assignment = useAssignment(requestId)
  const canAssignNow = canAssign && ASSIGNABLE_STATUSES.includes(status)
  const assignableUsers = useAssignableUsers(requestId, canAssignNow)
  const assign = useAssign(requestId)

  const [selected, setSelected] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  const current = assignment.data ?? null

  async function onAssign() {
    if (!selected) return
    setActionError(null)
    setCorrelationId(undefined)
    try {
      await assign.mutateAsync({ assigneeUserId: selected, expectedVersion: requestVersion })
      setSelected('')
    } catch (err) {
      if (err instanceof ApiClientError) {
        setActionError(err.message)
        setCorrelationId(err.correlationId)
      } else {
        setActionError('Could not assign the request.')
      }
    }
  }

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Assignment
        </Typography>

        <Typography variant="body2">
          {assignment.isPending ? (
            'Loading…'
          ) : current ? (
            <>
              Assigned to <strong>{current.assigneeName}</strong> ({current.assigneeRole})
            </>
          ) : (
            'Unassigned'
          )}
        </Typography>

        {canAssignNow && (
          <>
            <Divider sx={{ my: 2 }} />
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
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ alignItems: 'flex-start' }}>
              <TextField
                select
                label="Assign to"
                size="small"
                sx={{ minWidth: 240 }}
                slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
                value={selected}
                onChange={(e) => setSelected(e.target.value)}
              >
                <option value="">
                  {assignableUsers.isPending ? 'Loading…' : 'Select a provider or reviewer'}
                </option>
                {(assignableUsers.data ?? []).map((u) => (
                  <option key={u.userId} value={u.userId}>
                    {u.fullName} ({u.role})
                  </option>
                ))}
              </TextField>
              <Button
                variant="contained"
                size="small"
                disabled={!selected || assign.isPending}
                onClick={() => void onAssign()}
              >
                {current ? 'Reassign' : 'Assign'}
              </Button>
            </Stack>
          </>
        )}
      </CardContent>
    </Card>
  )
}

const commentSchema = z.object({
  body: z.string().trim().min(1, 'Required').max(2000, 'At most 2000 characters'),
})
type CommentForm = z.infer<typeof commentSchema>

/**
 * The request's comment thread (oldest first) plus an add box for participant roles. The role gate is
 * a UI convenience mirroring the backend rule — the server authorizes every add.
 */
function CommentsCard({ requestId, canComment }: { requestId: string; canComment: boolean }) {
  const comments = useComments(requestId)
  const addComment = useAddComment(requestId)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CommentForm>({ resolver: zodResolver(commentSchema), defaultValues: { body: '' } })

  async function onSubmit(values: CommentForm) {
    setSubmitError(null)
    setCorrelationId(undefined)
    try {
      await addComment.mutateAsync({ body: values.body })
      reset()
    } catch (err) {
      if (err instanceof ApiClientError) {
        setSubmitError(err.message)
        setCorrelationId(err.correlationId)
      } else {
        setSubmitError('Could not add the comment.')
      }
    }
  }

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Comments
        </Typography>

        {comments.isPending ? (
          <Typography variant="body2" color="text.secondary">
            Loading…
          </Typography>
        ) : comments.isError ? (
          <Typography variant="body2" color="error">
            Could not load comments.
          </Typography>
        ) : comments.data.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No comments yet.
          </Typography>
        ) : (
          <Stack spacing={1.5} divider={<Divider flexItem />}>
            {comments.data.map((c) => (
              <Box key={c.id}>
                <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
                  {c.body}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {new Date(c.createdAt).toLocaleString()}
                </Typography>
              </Box>
            ))}
          </Stack>
        )}

        {canComment && (
          <>
            <Divider sx={{ my: 2 }} />
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
              <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
                <TextField
                  label="Add a comment"
                  size="small"
                  fullWidth
                  multiline
                  minRows={2}
                  {...register('body')}
                  error={!!errors.body}
                  helperText={errors.body?.message}
                />
                <Button type="submit" variant="contained" size="small" disabled={addComment.isPending}>
                  {addComment.isPending ? 'Posting…' : 'Comment'}
                </Button>
              </Stack>
            </Box>
          </>
        )}
      </CardContent>
    </Card>
  )
}

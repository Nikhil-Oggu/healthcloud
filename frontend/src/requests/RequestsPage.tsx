import { useState } from 'react'
import type { ReactNode } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
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
import AddOutlinedIcon from '@mui/icons-material/AddOutlined'
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined'
import { ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { usePatients } from '../patients/usePatients'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { PageHeading } from '../components/PageHeading'
import { statusColor } from './statusColor'
import { useCreateRequest, useRequests } from './useRequests'

const CREATE_ROLES = ['PATIENT', 'PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN']
const TYPES = ['CLAIM_SUPPORT', 'REFERRAL_REQUEST', 'DOCUMENT_REVIEW', 'APPOINTMENT_HELP', 'BENEFIT_CLARIFICATION'] as const
const PRIORITIES = ['LOW', 'NORMAL', 'HIGH', 'URGENT'] as const

/** Turn an ALL_CAPS enum value into a readable label — CLAIM_SUPPORT → "Claim support", NORMAL → "Normal". */
const humanize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1).toLowerCase().replace(/_/g, ' ')

const createSchema = z.object({
  patientId: z.string().min(1, 'Required'),
  type: z.enum(TYPES),
  priority: z.enum(PRIORITIES),
  title: z.string().trim().min(1, 'Required').max(200, 'At most 200 characters'),
  description: z.string().trim().max(2000, 'At most 2000 characters').optional(),
})
type CreateForm = z.infer<typeof createSchema>

export function RequestsPage() {
  const { data: user } = useCurrentUser()
  const requests = useRequests()
  const canCreate = (user?.roles ?? []).some((r) => CREATE_ROLES.includes(r))

  if (requests.isPending) return <LoadingScreen />
  if (requests.isError) return <ErrorScreen error={requests.error} />

  return (
    <Stack spacing={3}>
      {/* Breadcrumb */}
      <Box>
        <Typography variant="body2" color="text.secondary">
          {user?.organizationName ?? '—'}
          <Box component="span" sx={{ mx: 1, opacity: 0.6 }}>
            /
          </Box>
          Care
        </Typography>
        <Divider sx={{ mt: 1.5 }} />
      </Box>

      {/* Title + subtitle */}
      <Box>
        <PageHeading sx={{ mb: 0.5 }}>Service requests</PageHeading>
        <Typography color="text.secondary">A simple workspace for every care request.</Typography>
      </Box>

      {canCreate && <CreateRequestForm />}

      {/* Request history */}
      <Box>
        <Stack direction="row" spacing={1.5} sx={{ mb: 1.5, alignItems: 'center', justifyContent: 'space-between' }}>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
              Request history
            </Typography>
            <Chip label={requests.data.length} size="small" />
          </Stack>
          <Link component={RouterLink} to="/requests" sx={{ fontWeight: 600 }} underline="hover">
            All requests
          </Link>
        </Stack>

        <TableContainer component={Paper} elevation={0}>
          <Table aria-label="Service requests">
            <TableHead>
              <TableRow>
                <TableCell>Title</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Priority</TableCell>
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {requests.data.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4} sx={{ borderBottom: 'none' }}>
                    <Stack direction="row" spacing={2} sx={{ py: 2, alignItems: 'center' }}>
                      <Box
                        sx={{
                          width: 48,
                          height: 48,
                          borderRadius: 2,
                          flexShrink: 0,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          color: 'primary.main',
                          bgcolor: 'rgba(13,148,136,0.10)',
                        }}
                      >
                        <DescriptionOutlinedIcon />
                      </Box>
                      <Box>
                        <Typography sx={{ fontWeight: 700 }}>No requests yet</Typography>
                        <Typography variant="body2" color="text.secondary">
                          Once created, your requests will appear here.
                        </Typography>
                      </Box>
                    </Stack>
                  </TableCell>
                </TableRow>
              ) : (
                requests.data.map((r) => (
                  <TableRow key={r.id} hover>
                    <TableCell>
                      <Link component={RouterLink} to={`/requests/${r.id}`} sx={{ fontWeight: 600 }} underline="hover">
                        {r.title}
                      </Link>
                    </TableCell>
                    <TableCell>{humanize(r.type)}</TableCell>
                    <TableCell>{humanize(r.priority)}</TableCell>
                    <TableCell>
                      <Chip label={r.status} size="small" color={statusColor(r.status)} />
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Box>
    </Stack>
  )
}

/** A form field with the label sitting above the control (the design's label-on-top style). */
function Field({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography
        component="label"
        htmlFor={id}
        variant="body2"
        sx={{ display: 'block', mb: 0.75, fontWeight: 500, color: 'text.secondary' }}
      >
        {label}
      </Typography>
      {children}
    </Box>
  )
}

function CreateRequestForm() {
  const patients = usePatients()
  const createRequest = useCreateRequest()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateForm>({
    resolver: zodResolver(createSchema),
    defaultValues: { patientId: '', type: 'CLAIM_SUPPORT', priority: 'NORMAL', title: '', description: '' },
  })

  async function onSubmit(values: CreateForm) {
    setSubmitError(null)
    try {
      await createRequest.mutateAsync(values)
      reset()
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'Could not create the request.')
    }
  }

  return (
    <Card>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
          </Alert>
        )}

        <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack spacing={2.5}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Field id="req-patientId" label="Patient">
                <TextField
                  id="req-patientId"
                  select
                  size="small"
                  fullWidth
                  slotProps={{ select: { native: true } }}
                  {...register('patientId')}
                  error={!!errors.patientId}
                  helperText={errors.patientId?.message}
                >
                  <option value="" disabled>
                    {patients.isPending ? 'Loading…' : 'Select a patient'}
                  </option>
                  {(patients.data ?? []).map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.fullName} ({p.medicalRecordNumber})
                    </option>
                  ))}
                </TextField>
              </Field>
              <Field id="req-type" label="Request type">
                <TextField id="req-type" select size="small" fullWidth slotProps={{ select: { native: true } }} {...register('type')}>
                  {TYPES.map((t) => (
                    <option key={t} value={t}>
                      {humanize(t)}
                    </option>
                  ))}
                </TextField>
              </Field>
              <Field id="req-priority" label="Priority">
                <TextField id="req-priority" select size="small" fullWidth slotProps={{ select: { native: true } }} {...register('priority')}>
                  {PRIORITIES.map((p) => (
                    <option key={p} value={p}>
                      {humanize(p)}
                    </option>
                  ))}
                </TextField>
              </Field>
            </Stack>

            <Field id="req-title" label="Request title">
              <TextField
                id="req-title"
                size="small"
                fullWidth
                placeholder="What does this patient need?"
                {...register('title')}
                error={!!errors.title}
                helperText={errors.title?.message}
              />
            </Field>

            <Field id="req-description" label="Description">
              <TextField
                id="req-description"
                size="small"
                fullWidth
                multiline
                minRows={2}
                placeholder="Add context for the care team"
                {...register('description')}
                error={!!errors.description}
                helperText={errors.description?.message}
              />
            </Field>

            <Divider sx={{ mx: -3, my: 0.5 }} />

            <Stack direction="row" spacing={1.5} sx={{ justifyContent: 'flex-end' }}>
              <Button
                variant="outlined"
                color="inherit"
                onClick={() => {
                  reset()
                  setSubmitError(null)
                }}
                disabled={createRequest.isPending}
              >
                Clear
              </Button>
              <Button type="submit" variant="contained" startIcon={<AddOutlinedIcon />} disabled={createRequest.isPending}>
                {createRequest.isPending ? 'Creating…' : 'Create request'}
              </Button>
            </Stack>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

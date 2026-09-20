import { useState } from 'react'
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
import { usePatients } from '../patients/usePatients'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { statusColor } from './statusColor'
import { useCreateRequest, useRequests } from './useRequests'
import { PageHeading } from '../components/PageHeading'

const CREATE_ROLES = ['PATIENT', 'PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN']
const TYPES = ['CLAIM_SUPPORT', 'REFERRAL_REQUEST', 'DOCUMENT_REVIEW', 'APPOINTMENT_HELP', 'BENEFIT_CLARIFICATION'] as const
const PRIORITIES = ['LOW', 'NORMAL', 'HIGH', 'URGENT'] as const

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
      <PageHeading>Service requests</PageHeading>

      {canCreate && <CreateRequestForm />}

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
                <TableCell colSpan={4}>
                  <Typography variant="body2" color="text.secondary">
                    No requests yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              requests.data.map((r) => (
                <TableRow key={r.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/requests/${r.id}`}>
                      {r.title}
                    </Link>
                  </TableCell>
                  <TableCell>{r.type}</TableCell>
                  <TableCell>{r.priority}</TableCell>
                  <TableCell>
                    <Chip label={r.status} size="small" color={statusColor(r.status)} />
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
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
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          New request
        </Typography>
        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
          </Alert>
        )}
        <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack spacing={2}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                select
                label="Patient"
                size="small"
                fullWidth
                defaultValue=""
                slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
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
              <TextField select label="Type" size="small" fullWidth slotProps={{ select: { native: true }, inputLabel: { shrink: true } }} {...register('type')}>
                {TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </TextField>
              <TextField select label="Priority" size="small" fullWidth slotProps={{ select: { native: true }, inputLabel: { shrink: true } }} {...register('priority')}>
                {PRIORITIES.map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </TextField>
            </Stack>
            <TextField
              label="Title"
              size="small"
              fullWidth
              {...register('title')}
              error={!!errors.title}
              helperText={errors.title?.message}
            />
            <TextField
              label="Description"
              size="small"
              fullWidth
              multiline
              minRows={2}
              {...register('description')}
              error={!!errors.description}
              helperText={errors.description?.message}
            />
            <Box>
              <Button type="submit" variant="contained" disabled={createRequest.isPending}>
                {createRequest.isPending ? 'Creating…' : 'Create'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

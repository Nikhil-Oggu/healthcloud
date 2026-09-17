import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Link as RouterLink } from 'react-router-dom'
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
import { api, ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { useCreatePatient, usePatients } from './usePatients'

const WRITE_ROLES = ['CARE_COORDINATOR', 'ORG_ADMIN']

// Client-side validation mirrors the backend Jakarta rules (a convenience — the server re-validates).
const createSchema = z.object({
  medicalRecordNumber: z.string().trim().min(1, 'Required').max(32, 'At most 32 characters'),
  fullName: z.string().trim().min(1, 'Required').max(200, 'At most 200 characters'),
  dateOfBirth: z
    .string()
    .min(1, 'Required')
    .refine((v) => new Date(v) < new Date(), 'Must be in the past'),
})

type CreateForm = z.infer<typeof createSchema>

/**
 * The tenant's patients. Any same-tenant user can view the list; only coordinators/admins see the
 * "Add patient" form (role-aware UI mirroring the backend authorization — the server still enforces it).
 */
export function PatientsPage() {
  const { data: user } = useCurrentUser()
  const patients = usePatients()
  const canWrite = (user?.roles ?? []).some((r) => WRITE_ROLES.includes(r))
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState<string | null>(null)

  // Download the patient list as CSV. The backend scopes + masks the file (a provider gets only their assigned
  // patients; a masked DOB is a blank cell), so this is safe to offer to any viewer. Mirrors the document download.
  async function onExport() {
    setExportError(null)
    setExporting(true)
    try {
      const blob = await api.exportPatientsCsv()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'patients.csv'
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (err) {
      setExportError(err instanceof ApiClientError ? err.message : 'Could not export patients.')
    } finally {
      setExporting(false)
    }
  }

  if (patients.isPending) {
    return <LoadingScreen />
  }
  if (patients.isError) {
    return <ErrorScreen error={patients.error} />
  }

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', rowGap: 1 }}>
        <Typography variant="h5">Patients</Typography>
        <Button variant="outlined" onClick={onExport} disabled={exporting || patients.data.length === 0}>
          {exporting ? 'Exporting…' : 'Export CSV'}
        </Button>
      </Stack>

      {exportError && <Alert severity="error">{exportError}</Alert>}

      {canWrite && <AddPatientForm />}

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Patients">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>MRN</TableCell>
              <TableCell>Date of birth</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {patients.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4}>
                  <Typography variant="body2" color="text.secondary">
                    No patients yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              patients.data.map((p) => (
                <TableRow key={p.id}>
                  <TableCell>
                    <Link component={RouterLink} to={`/patients/${p.id}`}>
                      {p.fullName}
                    </Link>
                  </TableCell>
                  <TableCell>{p.medicalRecordNumber}</TableCell>
                  <TableCell>
                    {p.dateOfBirth ?? (
                      <Typography component="span" variant="body2" color="text.secondary" sx={{ fontStyle: 'italic' }}>
                        Restricted
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell>
                    <Chip
                      label={p.status}
                      size="small"
                      color={p.status === 'ACTIVE' ? 'success' : 'default'}
                    />
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

function AddPatientForm() {
  const createPatient = useCreatePatient()
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [correlationId, setCorrelationId] = useState<string | undefined>(undefined)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateForm>({
    resolver: zodResolver(createSchema),
    defaultValues: { medicalRecordNumber: '', fullName: '', dateOfBirth: '' },
  })

  async function onSubmit(values: CreateForm) {
    setSubmitError(null)
    setCorrelationId(undefined)
    try {
      await createPatient.mutateAsync(values)
      reset()
    } catch (err) {
      // Surface the backend's message + correlationId (e.g. 409 duplicate MRN, 403).
      if (err instanceof ApiClientError) {
        setSubmitError(err.message)
        setCorrelationId(err.correlationId)
      } else {
        setSubmitError('Could not create the patient. Please try again.')
      }
    }
  }

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Add patient
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
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: 'flex-start' }}>
            <TextField
              label="Full name"
              size="small"
              fullWidth
              {...register('fullName')}
              error={!!errors.fullName}
              helperText={errors.fullName?.message}
            />
            <TextField
              label="MRN"
              size="small"
              {...register('medicalRecordNumber')}
              error={!!errors.medicalRecordNumber}
              helperText={errors.medicalRecordNumber?.message}
            />
            <TextField
              label="Date of birth"
              type="date"
              size="small"
              slotProps={{ inputLabel: { shrink: true } }}
              {...register('dateOfBirth')}
              error={!!errors.dateOfBirth}
              helperText={errors.dateOfBirth?.message}
            />
            <Button type="submit" variant="contained" disabled={createPatient.isPending}>
              {createPatient.isPending ? 'Adding…' : 'Add'}
            </Button>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

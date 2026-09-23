import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Link as RouterLink } from 'react-router-dom'
import {
  Alert,
  Avatar,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  InputAdornment,
  List,
  ListItemButton,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import type { ChipProps } from '@mui/material'
import SearchOutlinedIcon from '@mui/icons-material/SearchOutlined'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import OpenInNewOutlinedIcon from '@mui/icons-material/OpenInNewOutlined'
import { api, ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { EmptyState } from '../components/EmptyState'
import { PageHeading } from '../components/PageHeading'
import { useCreatePatient, usePatients } from './usePatients'
import type { Patient } from '../api/types'

const WRITE_ROLES = ['CARE_COORDINATOR', 'ORG_ADMIN']

const statusColor = (status: Patient['status']): ChipProps['color'] => (status === 'ACTIVE' ? 'success' : 'default')

/** Two-letter initials from a name, e.g. "Sam Sample" → "SS". */
function initials(name: string) {
  const parts = name.trim().split(/\s+/)
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || '?'
}

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
 * The tenant's patients, as a directory + overview. Any same-tenant user can view the list; only
 * coordinators/admins see the "Add patient" form (role-aware UI mirroring the backend authorization —
 * the server still enforces it). The DOB is consent-masked on the backend (§23) — a null value renders
 * as "Restricted"; we never trust the client to hide a value it received.
 */
export function PatientsPage() {
  const { data: user } = useCurrentUser()
  const patients = usePatients()
  const canWrite = (user?.roles ?? []).some((r) => WRITE_ROLES.includes(r))
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)

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

  const all = patients.data
  const q = query.trim().toLowerCase()
  const filtered = q
    ? all.filter(
        (p) => p.fullName.toLowerCase().includes(q) || p.medicalRecordNumber.toLowerCase().includes(q),
      )
    : all
  const selected = filtered.find((p) => p.id === selectedId) ?? filtered[0] ?? null

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

      {/* Title + subtitle + export */}
      <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', rowGap: 1 }}>
        <Box>
          <PageHeading sx={{ mb: 0.5 }}>Patients</PageHeading>
          <Typography color="text.secondary">Find and review your patient records.</Typography>
        </Box>
        <Button variant="outlined" onClick={onExport} disabled={exporting || all.length === 0}>
          {exporting ? 'Exporting…' : 'Export CSV'}
        </Button>
      </Stack>

      {exportError && <Alert severity="error">{exportError}</Alert>}

      {/* Directory + overview */}
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 3, alignItems: 'start' }}>
        <Card>
          <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 2 }}>
              <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
                Patient directory
              </Typography>
              <Chip
                label={`${all.length} ${all.length === 1 ? 'patient' : 'patients'}`}
                size="small"
                sx={{ bgcolor: 'rgba(13,148,136,0.10)', color: 'primary.dark', fontWeight: 600 }}
              />
            </Stack>

            <TextField
              fullWidth
              size="small"
              placeholder="Search by name or MRN"
              aria-label="Search patients by name or MRN"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              slotProps={{
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchOutlinedIcon fontSize="small" />
                    </InputAdornment>
                  ),
                },
              }}
            />

            {filtered.length === 0 ? (
              <EmptyState message={all.length === 0 ? 'No patients yet.' : 'No patients match your search.'} />
            ) : (
              <List disablePadding sx={{ mt: 2 }}>
                {filtered.map((p) => (
                  <ListItemButton
                    key={p.id}
                    selected={selected?.id === p.id}
                    onClick={() => setSelectedId(p.id)}
                    sx={{
                      borderRadius: 2,
                      mb: 1,
                      py: 1.25,
                      gap: 1.5,
                      border: 1,
                      borderColor: 'divider',
                      '&.Mui-selected': {
                        borderColor: 'primary.main',
                        borderLeftWidth: 3,
                        bgcolor: 'rgba(13,148,136,0.06)',
                        '&:hover': { bgcolor: 'rgba(13,148,136,0.10)' },
                      },
                    }}
                  >
                    <Avatar sx={{ bgcolor: 'rgba(13,148,136,0.12)', color: 'primary.dark', fontWeight: 700, fontSize: 14 }}>
                      {initials(p.fullName)}
                    </Avatar>
                    <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                      <Typography sx={{ fontWeight: 600 }} noWrap>
                        {p.fullName}
                      </Typography>
                      <Typography variant="body2" color="text.secondary" noWrap>
                        {p.medicalRecordNumber}
                      </Typography>
                    </Box>
                    <Chip label={p.status} size="small" color={statusColor(p.status)} />
                  </ListItemButton>
                ))}
              </List>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
            <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
              Patient overview
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Selected patient
            </Typography>

            {selected ? (
              <>
                <Stack direction="row" spacing={2.5} sx={{ alignItems: 'center', mt: 3 }}>
                  <Avatar
                    sx={{ width: 72, height: 72, bgcolor: 'rgba(13,148,136,0.12)', color: 'primary.dark', fontWeight: 700, fontSize: 24 }}
                  >
                    {initials(selected.fullName)}
                  </Avatar>
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="h5" component="p" sx={{ fontWeight: 700 }} noWrap>
                      {selected.fullName}
                    </Typography>
                    <Typography color="text.secondary">{selected.medicalRecordNumber}</Typography>
                    <Chip label={selected.status} size="small" color={statusColor(selected.status)} sx={{ mt: 0.5 }} />
                  </Box>
                </Stack>

                <Divider sx={{ my: 3 }} />

                <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>
                  Date of birth
                </Typography>
                {selected.dateOfBirth ? (
                  <Typography sx={{ fontWeight: 600 }}>{selected.dateOfBirth}</Typography>
                ) : (
                  <>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', color: 'text.secondary' }}>
                      <LockOutlinedIcon fontSize="small" />
                      <Typography sx={{ fontStyle: 'italic' }}>Restricted</Typography>
                    </Stack>
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
                      Some fields are restricted for your current access.
                    </Typography>
                  </>
                )}

                <Button
                  variant="contained"
                  component={RouterLink}
                  to={`/patients/${selected.id}`}
                  startIcon={<OpenInNewOutlinedIcon />}
                  sx={{ mt: 3 }}
                >
                  Open patient record
                </Button>
              </>
            ) : (
              <EmptyState message="Select a patient to see their overview." />
            )}
          </CardContent>
        </Card>
      </Box>

      {canWrite && <AddPatientForm />}
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
    <Card>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 2 }}>
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
              sx={{ minWidth: 175 }}
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

import { useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { ApiClientError } from '../api/client'
import { usePatients } from '../patients/usePatients'
import { useCoveragePlans } from '../coverage/useCoverage'
import { MedicalCodePicker } from '../claims/MedicalCodePicker'
import { useCreatePriorAuthorization } from './usePriorAuth'

const createSchema = z
  .object({
    patientId: z.string().min(1, 'Required'),
    coveragePlanId: z.string().min(1, 'Required'),
    procedureCode: z.string().trim().min(1, 'Required').max(16, 'At most 16 characters'),
    requestedServiceFrom: z.string().min(1, 'Required'),
    requestedServiceTo: z.string().optional(),
  })
  // Mirror the backend/DB window check: an end date must not precede the start.
  .refine((v) => !v.requestedServiceTo || v.requestedServiceTo >= v.requestedServiceFrom, {
    path: ['requestedServiceTo'],
    message: 'Cannot be before the start',
  })
// All fields are strings (no coercion), so input and output types coincide.
type CreateForm = z.infer<typeof createSchema>

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

export function CreatePriorAuthForm() {
  const patients = usePatients()
  const plans = useCoveragePlans()
  const createAuth = useCreatePriorAuthorization()
  const navigate = useNavigate()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateForm>({
    resolver: zodResolver(createSchema),
    defaultValues: {
      patientId: '',
      coveragePlanId: '',
      procedureCode: '',
      requestedServiceFrom: '',
      requestedServiceTo: '',
    },
  })

  async function onSubmit(values: CreateForm) {
    setSubmitError(null)
    try {
      const created = await createAuth.mutateAsync({
        patientId: values.patientId,
        coveragePlanId: values.coveragePlanId,
        procedureCode: values.procedureCode,
        requestedServiceFrom: values.requestedServiceFrom,
        requestedServiceTo: values.requestedServiceTo ? values.requestedServiceTo : undefined,
      })
      reset()
      navigate(`/prior-authorizations/${created.id}`)
    } catch (err) {
      setSubmitError(
        err instanceof ApiClientError ? err.message : 'Could not request the prior authorization.',
      )
    }
  }

  return (
    <Card sx={{ height: '100%' }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        {/* Card header */}
        <Box sx={{ mb: 3 }}>
          <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
            New authorization request
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Add patient, coverage, and service details.
          </Typography>
        </Box>

        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
          </Alert>
        )}

        <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack spacing={2.5}>
            <Field id="pa-patientId" label="Patient">
              <TextField
                id="pa-patientId"
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

            <Field id="pa-coveragePlanId" label="Coverage plan">
              <TextField
                id="pa-coveragePlanId"
                select
                size="small"
                fullWidth
                slotProps={{ select: { native: true } }}
                {...register('coveragePlanId')}
                error={!!errors.coveragePlanId}
                helperText={errors.coveragePlanId?.message}
              >
                <option value="" disabled>
                  {plans.isPending ? 'Loading…' : 'Select a plan'}
                </option>
                {(plans.data ?? []).map((pl) => (
                  <option key={pl.id} value={pl.id}>
                    {pl.name} ({pl.planCode})
                  </option>
                ))}
              </TextField>
            </Field>

            <Box sx={{ '& .MuiAutocomplete-root': { width: '100%' } }}>
              <Controller
                control={control}
                name="procedureCode"
                render={({ field }) => (
                  <MedicalCodePicker
                    value={field.value}
                    onChange={field.onChange}
                    error={!!errors.procedureCode}
                    helperText={errors.procedureCode?.message}
                  />
                )}
              />
            </Box>

            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Field id="pa-serviceFrom" label="Service from">
                <TextField
                  id="pa-serviceFrom"
                  type="date"
                  size="small"
                  fullWidth
                  slotProps={{ inputLabel: { shrink: true } }}
                  {...register('requestedServiceFrom')}
                  error={!!errors.requestedServiceFrom}
                  helperText={errors.requestedServiceFrom?.message}
                />
              </Field>
              <Field id="pa-serviceTo" label="Service to (optional)">
                <TextField
                  id="pa-serviceTo"
                  type="date"
                  size="small"
                  fullWidth
                  slotProps={{ inputLabel: { shrink: true } }}
                  {...register('requestedServiceTo')}
                  error={!!errors.requestedServiceTo}
                  helperText={errors.requestedServiceTo?.message}
                />
              </Field>
            </Stack>

            <Stack direction="row" spacing={1.5} sx={{ pt: 0.5 }}>
              <Button
                variant="outlined"
                color="inherit"
                onClick={() => {
                  reset()
                  setSubmitError(null)
                }}
                disabled={createAuth.isPending}
              >
                Clear
              </Button>
              <Button type="submit" variant="contained" disabled={createAuth.isPending}>
                {createAuth.isPending ? 'Requesting…' : 'Request authorization'}
              </Button>
            </Stack>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

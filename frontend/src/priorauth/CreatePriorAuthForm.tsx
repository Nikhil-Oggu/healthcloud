import { useState } from 'react'
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
              <TextField
                select
                label="Coverage plan"
                size="small"
                fullWidth
                defaultValue=""
                slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
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
            </Stack>

            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: 'flex-start' }}>
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
              <TextField
                label="Service from"
                type="date"
                size="small"
                slotProps={{ inputLabel: { shrink: true } }}
                {...register('requestedServiceFrom')}
                error={!!errors.requestedServiceFrom}
                helperText={errors.requestedServiceFrom?.message}
              />
              <TextField
                label="Service to (optional)"
                type="date"
                size="small"
                slotProps={{ inputLabel: { shrink: true } }}
                {...register('requestedServiceTo')}
                error={!!errors.requestedServiceTo}
                helperText={errors.requestedServiceTo?.message}
              />
            </Stack>

            <Box>
              <Button type="submit" variant="contained" disabled={createAuth.isPending}>
                {createAuth.isPending ? 'Requesting…' : 'Request'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

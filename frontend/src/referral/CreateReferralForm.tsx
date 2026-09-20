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
import { MedicalCodePicker } from '../claims/MedicalCodePicker'
import { useCreateReferral } from './useReferral'

const createSchema = z.object({
  patientId: z.string().min(1, 'Required'),
  specialty: z.string().trim().min(1, 'Required').max(100, 'At most 100 characters'),
  reasonCode: z.string().trim().min(1, 'Required').max(16, 'At most 16 characters'),
})
// All fields are strings (no coercion), so input and output types coincide.
type CreateForm = z.infer<typeof createSchema>

export function CreateReferralForm() {
  const patients = usePatients()
  const createReferral = useCreateReferral()
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
    defaultValues: { patientId: '', specialty: '', reasonCode: '' },
  })

  async function onSubmit(values: CreateForm) {
    setSubmitError(null)
    try {
      const created = await createReferral.mutateAsync({
        patientId: values.patientId,
        specialty: values.specialty,
        reasonCode: values.reasonCode,
      })
      reset()
      navigate(`/referrals/${created.id}`)
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'Could not request the referral.')
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
                label="Specialty"
                size="small"
                fullWidth
                placeholder="e.g. Cardiology"
                {...register('specialty')}
                error={!!errors.specialty}
                helperText={errors.specialty?.message}
              />
            </Stack>

            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: 'flex-start' }}>
              <Controller
                control={control}
                name="reasonCode"
                render={({ field }) => (
                  <MedicalCodePicker
                    value={field.value}
                    onChange={field.onChange}
                    label="Reason (diagnosis)"
                    category="Diagnosis"
                    error={!!errors.reasonCode}
                    helperText={errors.reasonCode?.message}
                  />
                )}
              />
            </Stack>

            <Box>
              <Button type="submit" variant="contained" disabled={createReferral.isPending}>
                {createReferral.isPending ? 'Requesting…' : 'Request'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

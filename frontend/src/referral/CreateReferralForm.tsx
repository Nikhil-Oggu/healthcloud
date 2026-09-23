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
import AddOutlinedIcon from '@mui/icons-material/AddOutlined'
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

/** A form field with the label sitting above the control (the design's label-on-top style). */
function Field({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  return (
    <Box>
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
    <Card sx={{ height: '100%' }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        {/* Card header */}
        <Stack direction="row" spacing={2} sx={{ mb: 3, alignItems: 'flex-start' }}>
          <Box
            sx={{
              width: 44,
              height: 44,
              borderRadius: 2,
              flexShrink: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'primary.main',
              bgcolor: 'rgba(13,148,136,0.10)',
            }}
          >
            <AddOutlinedIcon />
          </Box>
          <Box>
            <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
              Create a referral
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Choose a patient and add the referral details.
            </Typography>
          </Box>
        </Stack>

        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
          </Alert>
        )}

        <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack spacing={2.5}>
            <Field id="ref-patientId" label="Patient">
              <TextField
                id="ref-patientId"
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

            <Field id="ref-specialty" label="Specialty">
              <TextField
                id="ref-specialty"
                size="small"
                fullWidth
                placeholder="Enter specialty"
                {...register('specialty')}
                error={!!errors.specialty}
                helperText={errors.specialty?.message}
              />
            </Field>

            <Box sx={{ '& .MuiAutocomplete-root': { width: '100%' } }}>
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
            </Box>

            <Stack direction="row" spacing={1.5} sx={{ pt: 0.5 }}>
              <Button
                variant="outlined"
                color="inherit"
                onClick={() => {
                  reset()
                  setSubmitError(null)
                }}
                disabled={createReferral.isPending}
              >
                Clear
              </Button>
              <Button type="submit" variant="contained" disabled={createReferral.isPending}>
                {createReferral.isPending ? 'Requesting…' : 'Request referral'}
              </Button>
            </Stack>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

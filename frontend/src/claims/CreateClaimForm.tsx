import { useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Controller, useFieldArray, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  IconButton,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import AddOutlinedIcon from '@mui/icons-material/AddOutlined'
import { ApiClientError } from '../api/client'
import { usePatients } from '../patients/usePatients'
import { MedicalCodePicker } from './MedicalCodePicker'
import { useCreateClaim } from './useClaims'
import { useProviders } from './useProviders'

const today = () => new Date().toISOString().slice(0, 10)

const lineSchema = z.object({
  procedureCode: z.string().trim().min(1, 'Required').max(16, 'At most 16 characters'),
  units: z.preprocess(
    (v) => (v === '' || v == null ? undefined : v),
    z.coerce.number().int('Whole number').positive('Must be > 0').optional(),
  ),
  chargeAmount: z.coerce.number({ message: 'Required' }).min(0, 'Must be ≥ 0'),
})

const createSchema = z.object({
  patientId: z.string().min(1, 'Required'),
  serviceDate: z
    .string()
    .min(1, 'Required')
    .refine((d) => d <= today(), 'Cannot be in the future'),
  renderingProviderId: z.string().optional(),
  lines: z.array(lineSchema).min(1, 'Add at least one line'),
})
// The Zod schema coerces number inputs, so the form's input type (raw fields) differs from its parsed output.
type CreateFormInput = z.input<typeof createSchema>
type CreateFormOutput = z.output<typeof createSchema>

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

export function CreateClaimForm() {
  const patients = usePatients()
  const providers = useProviders()
  const createClaim = useCreateClaim()
  const navigate = useNavigate()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateFormInput, unknown, CreateFormOutput>({
    resolver: zodResolver(createSchema),
    defaultValues: {
      patientId: '',
      serviceDate: today(),
      renderingProviderId: '',
      lines: [{ procedureCode: '', units: undefined, chargeAmount: 0 }],
    },
  })
  const lines = useFieldArray({ control, name: 'lines' })

  async function onSubmit(values: CreateFormOutput) {
    setSubmitError(null)
    try {
      const created = await createClaim.mutateAsync({
        patientId: values.patientId,
        serviceDate: values.serviceDate,
        // Optional: send a rendering provider only when one is chosen (blank → omit).
        renderingProviderId: values.renderingProviderId ? values.renderingProviderId : undefined,
        lines: values.lines.map((l) => ({
          procedureCode: l.procedureCode,
          units: l.units,
          chargeAmount: l.chargeAmount,
        })),
      })
      reset()
      navigate(`/claims/${created.id}`)
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'Could not create the claim.')
    }
  }

  return (
    <Card sx={{ height: '100%' }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        {/* Card header */}
        <Box sx={{ mb: 3 }}>
          <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
            Create a claim
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Add patient details and the services provided.
          </Typography>
        </Box>

        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
          </Alert>
        )}

        <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack spacing={2.5}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Field id="claim-patientId" label="Patient">
                <TextField
                  id="claim-patientId"
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
              <Field id="claim-serviceDate" label="Service date">
                <TextField
                  id="claim-serviceDate"
                  type="date"
                  size="small"
                  fullWidth
                  sx={{ minWidth: 168 }}
                  slotProps={{ htmlInput: { max: today() } }}
                  {...register('serviceDate')}
                  error={!!errors.serviceDate}
                  helperText={errors.serviceDate?.message}
                />
              </Field>
            </Stack>

            <Field id="claim-renderingProviderId" label="Rendering provider">
              <TextField
                id="claim-renderingProviderId"
                select
                size="small"
                fullWidth
                slotProps={{ select: { native: true } }}
                helperText="Optional — used to check the plan's network"
                {...register('renderingProviderId')}
              >
                <option value="">{providers.isPending ? 'Loading…' : '— None —'}</option>
                {(providers.data ?? []).map((p) => (
                  <option key={p.userId} value={p.userId}>
                    {p.fullName}
                  </option>
                ))}
              </TextField>
            </Field>

            <Divider sx={{ mx: -3 }} />

            <Box>
              <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
                Claim lines
              </Typography>
              {typeof errors.lines?.message === 'string' && (
                <Typography variant="caption" color="error">
                  {errors.lines.message}
                </Typography>
              )}
            </Box>

            {lines.fields.map((field, i) => (
              <Stack key={field.id} direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ alignItems: 'flex-start' }}>
                <Controller
                  control={control}
                  name={`lines.${i}.procedureCode`}
                  render={({ field: f }) => (
                    <MedicalCodePicker
                      value={f.value}
                      onChange={f.onChange}
                      error={!!errors.lines?.[i]?.procedureCode}
                      helperText={errors.lines?.[i]?.procedureCode?.message}
                    />
                  )}
                />
                <TextField
                  label="Units"
                  type="number"
                  size="small"
                  sx={{ width: 100 }}
                  {...register(`lines.${i}.units`)}
                  error={!!errors.lines?.[i]?.units}
                  helperText={errors.lines?.[i]?.units?.message}
                />
                <TextField
                  label="Charge"
                  type="number"
                  size="small"
                  sx={{ width: 140 }}
                  slotProps={{ htmlInput: { step: '0.01', min: '0' } }}
                  {...register(`lines.${i}.chargeAmount`)}
                  error={!!errors.lines?.[i]?.chargeAmount}
                  helperText={errors.lines?.[i]?.chargeAmount?.message}
                />
                <IconButton
                  aria-label="Remove line"
                  size="small"
                  disabled={lines.fields.length === 1}
                  onClick={() => lines.remove(i)}
                >
                  ✕
                </IconButton>
              </Stack>
            ))}

            <Box>
              <Button
                size="small"
                onClick={() => lines.append({ procedureCode: '', units: undefined, chargeAmount: 0 })}
              >
                + Add line
              </Button>
            </Box>

            <Divider sx={{ mx: -3 }} />

            <Stack direction="row" spacing={1.5} sx={{ justifyContent: 'flex-end' }}>
              <Button
                variant="outlined"
                color="inherit"
                onClick={() => {
                  reset()
                  setSubmitError(null)
                }}
                disabled={createClaim.isPending}
              >
                Clear
              </Button>
              <Button type="submit" variant="contained" startIcon={<AddOutlinedIcon />} disabled={createClaim.isPending}>
                {createClaim.isPending ? 'Creating…' : 'Create claim'}
              </Button>
            </Stack>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

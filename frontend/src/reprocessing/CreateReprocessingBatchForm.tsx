import { useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
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
import { useCoveragePlans } from '../coverage/useCoverage'
import { useRunReprocessingBatch } from './useReprocessing'

const runSchema = z.object({
  coveragePlanId: z.string().min(1, 'Required'),
})
// A single string field — input and output types coincide.
type RunForm = z.infer<typeof runSchema>

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

export function CreateReprocessingBatchForm() {
  const plans = useCoveragePlans()
  const runBatch = useRunReprocessingBatch()
  const navigate = useNavigate()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RunForm>({
    resolver: zodResolver(runSchema),
    defaultValues: { coveragePlanId: '' },
  })

  async function onSubmit(values: RunForm) {
    setSubmitError(null)
    try {
      const created = await runBatch.mutateAsync({ coveragePlanId: values.coveragePlanId })
      reset()
      navigate(`/reprocessing/${created.id}`)
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'Could not run the batch.')
    }
  }

  return (
    <Card sx={{ borderTop: '3px solid', borderTopColor: 'primary.main' }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
          </Alert>
        )}

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
            gap: { xs: 3, md: 4 },
            alignItems: 'start',
          }}
        >
          {/* Left: what the batch does */}
          <Box>
            <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 1.5 }}>
              Run a batch
            </Typography>
            <Typography variant="body2" sx={{ mb: 1 }}>
              Re-adjudicate every already-adjudicated claim currently on the selected plan.
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Run after changes to the plan's fee schedule, exclusions, prior-auth requirements, or a patient's
              enrollment.
            </Typography>
          </Box>

          {/* Right: the form */}
          <Box
            component="form"
            onSubmit={handleSubmit(onSubmit)}
            noValidate
            sx={{
              borderLeft: { xs: 0, md: '1px solid' },
              borderColor: 'divider',
              pl: { xs: 0, md: 4 },
            }}
          >
            <Stack spacing={2.5}>
              <Field id="reprocessing-plan" label="Coverage plan">
                <TextField
                  id="reprocessing-plan"
                  select
                  size="small"
                  fullWidth
                  slotProps={{ select: { native: true } }}
                  {...register('coveragePlanId')}
                  error={!!errors.coveragePlanId}
                  helperText={errors.coveragePlanId?.message ?? 'The plan whose claims should be reprocessed.'}
                >
                  <option value="" disabled>
                    {plans.isPending ? 'Loading…' : 'Select a plan'}
                  </option>
                  {(plans.data ?? []).map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name} ({p.planCode})
                    </option>
                  ))}
                </TextField>
              </Field>

              <Box>
                <Button type="submit" variant="contained" disabled={runBatch.isPending}>
                  {runBatch.isPending ? 'Running…' : 'Run batch'}
                </Button>
              </Box>
            </Stack>
          </Box>
        </Box>
      </CardContent>
    </Card>
  )
}

import { useState } from 'react'
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
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          Run a batch
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Re-adjudicate every already-adjudicated claim currently on a plan — run this after changing the plan's
          fee schedule, exclusions, prior-auth requirements, or a patient's enrollment.
        </Typography>
        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
          </Alert>
        )}
        <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack spacing={2}>
            <TextField
              select
              label="Coverage plan"
              size="small"
              fullWidth
              defaultValue=""
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
            <Box>
              <Button type="submit" variant="contained" disabled={runBatch.isPending}>
                {runBatch.isPending ? 'Running…' : 'Run batch'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

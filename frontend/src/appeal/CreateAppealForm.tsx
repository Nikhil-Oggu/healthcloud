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
import { useClaims } from '../claims/useClaims'
import { useCreateAppeal } from './useAppeal'

// Claims a user may appeal: only those with a decision to dispute (the backend re-validates this).
const APPEALABLE = ['ADJUDICATED', 'REJECTED']

const createSchema = z.object({
  claimId: z.string().min(1, 'Required'),
  reason: z.string().trim().min(1, 'Required').max(1000, 'At most 1000 characters'),
})
// All fields are strings (no coercion), so input and output types coincide.
type CreateForm = z.infer<typeof createSchema>

export function CreateAppealForm() {
  const claims = useClaims()
  const createAppeal = useCreateAppeal()
  const navigate = useNavigate()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateForm>({
    resolver: zodResolver(createSchema),
    defaultValues: { claimId: '', reason: '' },
  })

  const appealableClaims = (claims.data ?? []).filter((c) => APPEALABLE.includes(c.status))

  async function onSubmit(values: CreateForm) {
    setSubmitError(null)
    try {
      const created = await createAppeal.mutateAsync({ claimId: values.claimId, reason: values.reason })
      reset()
      navigate(`/appeals/${created.id}`)
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'Could not submit the appeal.')
    }
  }

  return (
    <Card>
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          New appeal
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
              label="Claim"
              size="small"
              fullWidth
              defaultValue=""
              slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
              {...register('claimId')}
              error={!!errors.claimId}
              helperText={errors.claimId?.message ?? 'Only adjudicated or rejected claims can be appealed.'}
            >
              <option value="" disabled>
                {claims.isPending ? 'Loading…' : 'Select a claim'}
              </option>
              {appealableClaims.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.claimNumber} ({c.status})
                </option>
              ))}
            </TextField>
            <TextField
              label="Reason"
              size="small"
              fullWidth
              multiline
              minRows={2}
              placeholder="Why should this decision be reconsidered?"
              {...register('reason')}
              error={!!errors.reason}
              helperText={errors.reason?.message}
            />
            <Box>
              <Button type="submit" variant="contained" disabled={createAppeal.isPending}>
                {createAppeal.isPending ? 'Submitting…' : 'Submit'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

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
import { useCreateClaimReview } from './useClaimReview'

const createSchema = z.object({
  claimId: z.string().min(1, 'Required'),
  reason: z.string().trim().max(1000, 'At most 1000 characters').optional(),
})
// All fields are strings (no coercion), so input and output types coincide.
type CreateForm = z.infer<typeof createSchema>

export function CreateClaimReviewForm() {
  const claims = useClaims()
  const createReview = useCreateClaimReview()
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

  async function onSubmit(values: CreateForm) {
    setSubmitError(null)
    try {
      const created = await createReview.mutateAsync({
        claimId: values.claimId,
        reason: values.reason?.trim() ? values.reason.trim() : undefined,
      })
      reset()
      navigate(`/claim-reviews/${created.id}`)
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'Could not open the review.')
    }
  }

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
          New review
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
              helperText={errors.claimId?.message ?? 'Any claim can be flagged for a manual review.'}
            >
              <option value="" disabled>
                {claims.isPending ? 'Loading…' : 'Select a claim'}
              </option>
              {(claims.data ?? []).map((c) => (
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
              placeholder="Why is this claim being flagged for review? (optional)"
              {...register('reason')}
              error={!!errors.reason}
              helperText={errors.reason?.message}
            />
            <Box>
              <Button type="submit" variant="contained" disabled={createReview.isPending}>
                {createReview.isPending ? 'Opening…' : 'Open review'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

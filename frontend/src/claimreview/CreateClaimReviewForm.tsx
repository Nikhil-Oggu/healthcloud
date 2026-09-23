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
import { useClaims } from '../claims/useClaims'
import { useCreateClaimReview } from './useClaimReview'

const createSchema = z.object({
  claimId: z.string().min(1, 'Required'),
  reason: z.string().trim().max(1000, 'At most 1000 characters').optional(),
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
    <Card sx={{ height: '100%', borderTop: '3px solid', borderTopColor: 'primary.main' }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 3 }}>
          New review
        </Typography>

        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
          </Alert>
        )}

        <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack spacing={2.5}>
            <Field id="claim-review-claimId" label="Claim">
              <TextField
                id="claim-review-claimId"
                select
                size="small"
                fullWidth
                slotProps={{ select: { native: true } }}
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
            </Field>

            <Field id="claim-review-reason" label="Reason">
              <TextField
                id="claim-review-reason"
                size="small"
                fullWidth
                multiline
                minRows={4}
                placeholder="Why is this claim being flagged for review? (optional)"
                {...register('reason')}
                error={!!errors.reason}
                helperText={errors.reason?.message}
              />
            </Field>

            <Stack direction="row" spacing={1.5} sx={{ justifyContent: 'space-between' }}>
              <Button
                variant="outlined"
                color="inherit"
                onClick={() => {
                  reset()
                  setSubmitError(null)
                }}
                disabled={createReview.isPending}
              >
                Clear
              </Button>
              <Button type="submit" variant="contained" disabled={createReview.isPending}>
                {createReview.isPending ? 'Opening…' : 'Open review'}
              </Button>
            </Stack>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}

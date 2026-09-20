import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Alert, AlertTitle, Box, Button, Card, CardContent, Stack, TextField } from '@mui/material'
import { ApiClientError } from '../api/client'
import { useBreakGlass } from './useBreakGlass'

const schema = z.object({
  reason: z.string().min(1, 'A reason is required'),
})
type BreakGlassForm = z.infer<typeof schema>

/**
 * Shown in place of the generic error screen when a PROVIDER reaches a patient they are not assigned to (a secure
 * 404). It lets them break the glass for that patient — the id comes from the page URL — recording a justification.
 * On success the surrounding queries are invalidated (see {@link useBreakGlass}) so the page reloads with access.
 */
export function BreakGlassPanel({ patientId }: { patientId: string }) {
  const breakGlass = useBreakGlass(patientId)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<BreakGlassForm>({
    resolver: zodResolver(schema),
    defaultValues: { reason: '' },
  })

  async function onSubmit(values: BreakGlassForm) {
    setSubmitError(null)
    try {
      await breakGlass.mutateAsync({ patientId, reason: values.reason })
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'Could not break the glass.')
    }
  }

  return (
    <Box sx={{ maxWidth: 560, mx: 'auto', mt: 8, px: 2 }}>
      <Card>
        <CardContent>
          <Alert severity="warning" sx={{ mb: 2 }}>
            <AlertTitle>You do not have access to this patient</AlertTitle>
            In an emergency you may break the glass to gain <strong>time-boxed</strong> access. Every use is
            recorded in the audit trail and reviewed.
          </Alert>
          {submitError && (
            <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
              {submitError}
            </Alert>
          )}
          <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
            <Stack spacing={2}>
              <TextField
                label="Reason for emergency access"
                size="small"
                fullWidth
                multiline
                minRows={2}
                {...register('reason')}
                error={!!errors.reason}
                helperText={errors.reason?.message ?? 'Explain why you need access to this patient now.'}
              />
              <Box>
                <Button type="submit" variant="contained" color="warning" disabled={breakGlass.isPending}>
                  {breakGlass.isPending ? 'Breaking glass…' : 'Break glass'}
                </Button>
              </Box>
            </Stack>
          </Box>
        </CardContent>
      </Card>
    </Box>
  )
}

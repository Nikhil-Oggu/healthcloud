import { Alert, AlertTitle, Box, Typography } from '@mui/material'
import { ApiClientError } from '../api/client'

/**
 * Renders an error using the backend's contract: the human message plus the correlationId, which the
 * user can quote to support so the exact request can be found in the server logs.
 */
export function ErrorScreen({ error }: { error: unknown }) {
  const apiError = error instanceof ApiClientError ? error : null
  const title = apiError?.code ?? 'Error'
  const message = apiError?.message ?? 'Something went wrong. Please try again.'
  const correlationId = apiError?.correlationId

  return (
    <Box sx={{ maxWidth: 560, mx: 'auto', mt: 8, px: 2 }}>
      <Alert severity="error">
        <AlertTitle>{title}</AlertTitle>
        <Typography variant="body2">{message}</Typography>
        {correlationId && (
          <Typography variant="caption" sx={{ display: 'block', mt: 1, opacity: 0.8 }}>
            Reference ID: {correlationId}
          </Typography>
        )}
      </Alert>
    </Box>
  )
}

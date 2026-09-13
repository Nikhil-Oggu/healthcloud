import { Box, CircularProgress } from '@mui/material'

/** Full-height centered spinner shown while the session/user is being resolved. */
export function LoadingScreen() {
  return (
    <Box
      role="status"
      aria-label="Loading"
      sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '60vh' }}
    >
      <CircularProgress />
    </Box>
  )
}

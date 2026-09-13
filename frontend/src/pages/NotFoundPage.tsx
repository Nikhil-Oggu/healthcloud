import { Box, Button, Typography } from '@mui/material'
import { useNavigate } from 'react-router-dom'

/** Fallback for unknown routes. */
export function NotFoundPage() {
  const navigate = useNavigate()
  return (
    <Box sx={{ maxWidth: 560, mx: 'auto', mt: 6, textAlign: 'center' }}>
      <Typography variant="h5" gutterBottom>
        Page not found
      </Typography>
      <Button variant="contained" sx={{ mt: 2 }} onClick={() => navigate('/')}>
        Back to home
      </Button>
    </Box>
  )
}

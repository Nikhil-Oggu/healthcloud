import { Box, Button } from '@mui/material'
import { useNavigate } from 'react-router-dom'
import { PageHeading } from '../components/PageHeading'

/** Fallback for unknown routes. */
export function NotFoundPage() {
  const navigate = useNavigate()
  return (
    <Box sx={{ maxWidth: 560, mx: 'auto', mt: 6, textAlign: 'center' }}>
      <PageHeading gutterBottom>
        Page not found
      </PageHeading>
      <Button variant="contained" sx={{ mt: 2 }} onClick={() => navigate('/')}>
        Back to home
      </Button>
    </Box>
  )
}

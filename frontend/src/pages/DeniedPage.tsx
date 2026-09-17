import { Box, Button, Typography } from '@mui/material'
import { useNavigate } from 'react-router-dom'
import { PageHeading } from '../components/PageHeading'

/** Shown when the backend denies access (403) to a resource the user reached in the UI. */
export function DeniedPage() {
  const navigate = useNavigate()
  return (
    <Box sx={{ maxWidth: 560, mx: 'auto', mt: 6, textAlign: 'center' }}>
      <PageHeading gutterBottom>
        Access denied
      </PageHeading>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        You don’t have permission to view this. If you believe this is an error, contact your
        organization administrator.
      </Typography>
      <Button variant="contained" onClick={() => navigate('/')}>
        Back to home
      </Button>
    </Box>
  )
}

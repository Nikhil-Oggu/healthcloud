import { Box, Typography } from '@mui/material'
import InboxOutlinedIcon from '@mui/icons-material/InboxOutlined'

/**
 * A consistent, muted empty state for work-queue tables (and other lists). Render it inside a full-width
 * table cell (`colSpan`) in place of the old plain "No X yet." text.
 */
export function EmptyState({ message }: { message: string }) {
  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 1,
        py: 6,
        color: 'text.secondary',
        textAlign: 'center',
      }}
    >
      <InboxOutlinedIcon sx={{ fontSize: 36, opacity: 0.5 }} />
      <Typography variant="body2">{message}</Typography>
    </Box>
  )
}

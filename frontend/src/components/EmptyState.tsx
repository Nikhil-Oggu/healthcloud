import { Box, Typography } from '@mui/material'
import InboxOutlinedIcon from '@mui/icons-material/InboxOutlined'

/**
 * A consistent, muted empty state for work-queue tables (and other lists). Render it inside a full-width
 * table cell (`colSpan`) in place of the old plain "No X yet." text. Pass an optional `description` for a
 * muted second line (the primary `message` then reads as a bolder heading).
 */
export function EmptyState({ message, description }: { message: string; description?: string }) {
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
      <Typography
        variant="body2"
        sx={{ fontWeight: description ? 600 : 400, color: description ? 'text.primary' : 'text.secondary' }}
      >
        {message}
      </Typography>
      {description && (
        <Typography variant="body2" color="text.secondary">
          {description}
        </Typography>
      )}
    </Box>
  )
}

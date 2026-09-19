import { Box, Typography } from '@mui/material'

/**
 * The HealthCloud brand mark (Care Constellation identity): a gradient rounded-square glyph +
 * the wordmark in Space Grotesk. Use `compact` for the glyph alone (e.g. a collapsed sidebar).
 * See docs/design/design-system.md §1.
 */
export function Brand({
  compact = false,
  onDark = false,
}: {
  compact?: boolean
  onDark?: boolean
}) {
  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1 }}>
      <Box
        aria-hidden
        sx={{
          width: 26,
          height: 26,
          borderRadius: '8px',
          background: 'linear-gradient(120deg, #5eead4, #7c9cff)',
          boxShadow: '0 0 14px rgba(94,234,212,.45)',
          flexShrink: 0,
        }}
      />
      {!compact && (
        <Typography
          component="span"
          sx={{
            fontFamily: '"Space Grotesk", sans-serif',
            fontWeight: 700,
            fontSize: 18,
            letterSpacing: '-0.01em',
            color: onDark ? '#ffffff' : 'text.primary',
          }}
        >
          HealthCloud
        </Typography>
      )}
    </Box>
  )
}

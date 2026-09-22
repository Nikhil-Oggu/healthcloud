import { Box, Typography } from '@mui/material'

/**
 * The HealthCloud brand mark (Care Constellation identity): the logo glyph (`public/logo.png`, the
 * teal→blue→indigo rounded square with the white medical cross) + the wordmark in Space Grotesk.
 * Use `compact` for the glyph alone (e.g. a collapsed sidebar), and `size="lg"` for a larger mark
 * (e.g. the login hero). See docs/design/design-system.md §1.
 */
export function Brand({
  compact = false,
  onDark = false,
  size = 'md',
}: {
  compact?: boolean
  onDark?: boolean
  size?: 'md' | 'lg'
}) {
  const glyph = size === 'lg' ? 44 : 26
  const font = size === 'lg' ? 30 : 18

  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: size === 'lg' ? 1.5 : 1 }}>
      <Box
        component="img"
        src="/logo.png"
        alt=""
        aria-hidden
        sx={{ width: glyph, height: glyph, display: 'block', flexShrink: 0 }}
      />
      {!compact && (
        <Typography
          component="span"
          sx={{
            fontFamily: '"Space Grotesk", sans-serif',
            fontWeight: 700,
            fontSize: font,
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

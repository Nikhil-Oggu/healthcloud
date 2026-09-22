import { Box, Typography } from '@mui/material'

/**
 * The HealthCloud brand mark (Care Constellation identity): a gradient rounded-square glyph with a
 * white medical cross + the wordmark in Space Grotesk. Use `compact` for the glyph alone (e.g. a
 * collapsed sidebar), and `size="lg"` for a larger mark (e.g. the login hero).
 * See docs/design/design-system.md §1.
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
  const cross = Math.round(glyph * 0.62)

  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: size === 'lg' ? 1.5 : 1 }}>
      <Box
        aria-hidden
        sx={{
          width: glyph,
          height: glyph,
          borderRadius: size === 'lg' ? '13px' : '8px',
          background: 'linear-gradient(135deg, #31c1c5 0%, #3e8dd3 50%, #4b5ae0 100%)',
          boxShadow: '0 0 16px rgba(62,141,211,.38)',
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Box component="svg" viewBox="0 0 24 24" sx={{ width: cross, height: cross, display: 'block' }}>
          <rect x="10" y="4.5" width="4" height="15" rx="2" fill="#ffffff" />
          <rect x="4.5" y="10" width="15" height="4" rx="2" fill="#ffffff" />
        </Box>
      </Box>
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

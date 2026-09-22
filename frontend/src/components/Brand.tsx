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
  const glyph = size === 'lg' ? 40 : 26
  const font = size === 'lg' ? 26 : 18
  const cross = Math.round(glyph * 0.5)

  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: size === 'lg' ? 1.4 : 1 }}>
      <Box
        aria-hidden
        sx={{
          width: glyph,
          height: glyph,
          borderRadius: size === 'lg' ? '12px' : '8px',
          background: 'linear-gradient(135deg, #5eead4, #7c9cff)',
          boxShadow: '0 0 14px rgba(94,234,212,.45)',
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Box component="svg" viewBox="0 0 24 24" sx={{ width: cross, height: cross, display: 'block' }}>
          <path d="M12 5.5v13M5.5 12h13" stroke="#ffffff" strokeWidth={2.6} strokeLinecap="round" />
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

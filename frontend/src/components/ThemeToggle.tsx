import { useColorScheme } from '@mui/material/styles'
import { Box, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material'
import LightModeOutlinedIcon from '@mui/icons-material/LightModeOutlined'
import DarkModeOutlinedIcon from '@mui/icons-material/DarkModeOutlined'
import SettingsBrightnessOutlinedIcon from '@mui/icons-material/SettingsBrightnessOutlined'

const OPTIONS = [
  { value: 'light', label: 'Light', icon: <LightModeOutlinedIcon fontSize="small" /> },
  { value: 'system', label: 'System', icon: <SettingsBrightnessOutlinedIcon fontSize="small" /> },
  { value: 'dark', label: 'Dark', icon: <DarkModeOutlinedIcon fontSize="small" /> },
] as const

/**
 * Light / System / Dark theme switch (Slice 9), styled as a labelled segmented control with an
 * "Appearance" heading. Backed by MUI's `useColorScheme`, so:
 * - "System" follows the viewer's device preference (prefers-color-scheme),
 * - an explicit Light/Dark choice persists in localStorage across reloads,
 * - switching is instant (CSS variables), no reload.
 *
 * `mode` is `undefined` until the provider mounts (and when rendered without a CSS-vars provider, e.g.
 * in a unit test) — we render nothing in that case rather than flashing a wrong state. The "Appearance"
 * heading lives here (not in the caller) so it disappears together with the control in that case.
 */
export function ThemeToggle() {
  const { mode, setMode } = useColorScheme()
  if (!mode) return null

  return (
    <Box>
      <Typography
        sx={{
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: '0.08em',
          textTransform: 'uppercase',
          color: 'text.secondary',
          mb: 0.75,
        }}
      >
        Appearance
      </Typography>
      <ToggleButtonGroup
        size="small"
        exclusive
        fullWidth
        value={mode}
        onChange={(_, next: 'light' | 'dark' | 'system' | null) => next && setMode(next)}
        aria-label="Color theme"
        sx={{
          bgcolor: 'action.hover',
          borderRadius: 1.5,
          p: 0.375,
          gap: 0.375,
          '& .MuiToggleButtonGroup-grouped': {
            border: 0,
            borderRadius: 1,
            marginLeft: 0,
            flexDirection: 'row',
            gap: 0.5,
            py: 0.5,
            fontSize: 12,
            textTransform: 'none',
            fontWeight: 600,
            color: 'text.secondary',
            '& .MuiSvgIcon-root': { fontSize: 15 },
            '&:hover': { bgcolor: 'action.selected' },
            '&.Mui-selected': {
              bgcolor: 'background.paper',
              color: 'primary.main',
              boxShadow: 1,
              '&:hover': { bgcolor: 'background.paper' },
            },
          },
        }}
      >
        {OPTIONS.map((o) => (
          <ToggleButton key={o.value} value={o.value} aria-label={o.label}>
            {o.icon}
            <Typography component="span" sx={{ fontSize: 12, fontWeight: 'inherit', lineHeight: 1 }}>
              {o.label}
            </Typography>
          </ToggleButton>
        ))}
      </ToggleButtonGroup>
    </Box>
  )
}

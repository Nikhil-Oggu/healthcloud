import { useColorScheme } from '@mui/material/styles'
import { ToggleButton, ToggleButtonGroup, Tooltip } from '@mui/material'
import LightModeOutlinedIcon from '@mui/icons-material/LightModeOutlined'
import DarkModeOutlinedIcon from '@mui/icons-material/DarkModeOutlined'
import SettingsBrightnessOutlinedIcon from '@mui/icons-material/SettingsBrightnessOutlined'

/**
 * Light / Dark / System theme switch (Slice 9). Backed by MUI's `useColorScheme`, so:
 * - "System" follows the viewer's device preference (prefers-color-scheme),
 * - an explicit Light/Dark choice persists in localStorage across reloads,
 * - switching is instant (CSS variables), no reload.
 *
 * `mode` is `undefined` until the provider mounts (and when rendered without a CSS-vars provider, e.g.
 * in a unit test) — we render nothing in that case rather than flashing a wrong state.
 */
export function ThemeToggle() {
  const { mode, setMode } = useColorScheme()
  if (!mode) return null

  return (
    <ToggleButtonGroup
      size="small"
      exclusive
      fullWidth
      value={mode}
      onChange={(_, next: 'light' | 'dark' | 'system' | null) => next && setMode(next)}
      aria-label="Color theme"
    >
      <ToggleButton value="light" aria-label="Light">
        <Tooltip title="Light">
          <LightModeOutlinedIcon fontSize="small" />
        </Tooltip>
      </ToggleButton>
      <ToggleButton value="system" aria-label="Match device">
        <Tooltip title="Match device">
          <SettingsBrightnessOutlinedIcon fontSize="small" />
        </Tooltip>
      </ToggleButton>
      <ToggleButton value="dark" aria-label="Dark">
        <Tooltip title="Dark">
          <DarkModeOutlinedIcon fontSize="small" />
        </Tooltip>
      </ToggleButton>
    </ToggleButtonGroup>
  )
}

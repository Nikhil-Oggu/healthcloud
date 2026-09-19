import { Link } from '@mui/material'
import { Link as RouterLink } from 'react-router-dom'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'

/** A consistent "back to the list" affordance for detail pages: an arrow icon + label. */
export function BackLink({ to, label }: { to: string; label: string }) {
  return (
    <Link
      component={RouterLink}
      to={to}
      variant="body2"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.5,
        color: 'text.secondary',
        '&:hover': { color: 'primary.main' },
      }}
    >
      <ArrowBackIcon sx={{ fontSize: 18 }} />
      {label}
    </Link>
  )
}
